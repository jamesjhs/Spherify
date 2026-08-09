/*
 * spherify_stitcher.cpp
 *
 * Native PhotoSphere solver/compositor for Spherify. When the full native
 * OpenCV stitching module is available, Spherify delegates panorama solving and
 * compositing to OpenCV's own Stitcher pipeline. The app keeps responsibility
 * for capture gating and GPano certification around that library output.
 */
#include <jni.h>

#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/stitching.hpp>
#include <opencv2/stitching/detail/blenders.hpp>
#include <opencv2/stitching/detail/camera.hpp>
#include <opencv2/stitching/detail/exposure_compensate.hpp>
#include <opencv2/stitching/detail/matchers.hpp>
#include <opencv2/stitching/detail/seam_finders.hpp>
#include <opencv2/stitching/detail/warpers.hpp>
#include <opencv2/stitching/warpers.hpp>

namespace {

constexpr int STATUS_OK = 0;
constexpr int STATUS_NEED_MORE_IMAGES = 1;
constexpr int STATUS_CAMERA_ADJUST_FAILED = 3;
constexpr int STATUS_IO_FAILED = -10;

constexpr double WORK_MEGAPIX = 0.6;
constexpr double SEAM_MEGAPIX = 0.12;
constexpr double COMPOSE_MEGAPIX = -1.0;
constexpr float PANORAMA_CONFIDENCE = 1.0f;

struct CameraPrior {
    bool available = false;
    double focal = 0.0;
    double aspect = 1.0;
    double ppx = 0.0;
    double ppy = 0.0;
    cv::Mat rotation;
};

class SensorPriorEstimator final : public cv::detail::Estimator {
public:
    explicit SensorPriorEstimator(std::vector<CameraPrior> priors) : priors_(std::move(priors)) {}

private:
    bool estimate(
            const std::vector<cv::detail::ImageFeatures> &features,
            const std::vector<cv::detail::MatchesInfo> &,
            std::vector<cv::detail::CameraParams> &cameras) override {
        if (features.size() != priors_.size() || priors_.empty()) {
            return false;
        }
        cameras.assign(priors_.size(), cv::detail::CameraParams());
        for (size_t i = 0; i < priors_.size(); ++i) {
            const CameraPrior &prior = priors_[i];
            if (!prior.available || prior.focal <= 0.0 || prior.aspect <= 0.0 || prior.rotation.empty()) {
                return false;
            }
            cameras[i].focal = prior.focal;
            cameras[i].aspect = prior.aspect;
            cameras[i].ppx = prior.ppx;
            cameras[i].ppy = prior.ppy;
            prior.rotation.convertTo(cameras[i].R, CV_32F);
            cameras[i].t = cv::Mat::zeros(3, 1, CV_32F);
        }
        return true;
    }

    std::vector<CameraPrior> priors_;
};

std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? "" : chars;
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

std::vector<std::string> to_paths(JNIEnv *env, jobjectArray input_paths) {
    std::vector<std::string> paths;
    const jsize count = env->GetArrayLength(input_paths);
    paths.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(input_paths, i));
        paths.push_back(jstring_to_string(env, path));
        env->DeleteLocalRef(path);
    }
    return paths;
}

cv::UMat to_matching_mask(JNIEnv *env, jintArray input_mask, size_t image_count) {
    if (input_mask == nullptr || image_count == 0) {
        return {};
    }
    const jsize expected = static_cast<jsize>(image_count * image_count);
    if (env->GetArrayLength(input_mask) != expected) {
        return {};
    }
    std::vector<jint> values(static_cast<size_t>(expected));
    env->GetIntArrayRegion(input_mask, 0, expected, values.data());
    cv::Mat mask(static_cast<int>(image_count), static_cast<int>(image_count), CV_8U);
    for (int row = 0; row < mask.rows; ++row) {
        for (int col = 0; col < mask.cols; ++col) {
            mask.at<uchar>(row, col) = values[static_cast<size_t>(row * mask.cols + col)] != 0 ? 255 : 0;
        }
    }
    cv::UMat result;
    mask.copyTo(result);
    return result;
}

std::vector<CameraPrior> to_camera_priors(
        JNIEnv *env,
        jdoubleArray input_intrinsics,
        jdoubleArray input_rotations,
        jintArray input_available,
        size_t image_count) {
    std::vector<CameraPrior> priors(image_count);
    if (input_intrinsics == nullptr || input_rotations == nullptr || input_available == nullptr) {
        return priors;
    }
    const jsize intrinsics_count = static_cast<jsize>(image_count * 4);
    const jsize rotations_count = static_cast<jsize>(image_count * 9);
    if (env->GetArrayLength(input_intrinsics) != intrinsics_count ||
            env->GetArrayLength(input_rotations) != rotations_count ||
            env->GetArrayLength(input_available) != static_cast<jsize>(image_count)) {
        return priors;
    }
    std::vector<jdouble> intrinsics(static_cast<size_t>(intrinsics_count));
    std::vector<jdouble> rotations(static_cast<size_t>(rotations_count));
    std::vector<jint> available(image_count);
    env->GetDoubleArrayRegion(input_intrinsics, 0, intrinsics_count, intrinsics.data());
    env->GetDoubleArrayRegion(input_rotations, 0, rotations_count, rotations.data());
    env->GetIntArrayRegion(input_available, 0, static_cast<jsize>(image_count), available.data());
    for (size_t i = 0; i < image_count; ++i) {
        CameraPrior prior;
        prior.available = available[i] != 0;
        prior.focal = intrinsics[i * 4];
        prior.aspect = intrinsics[i * 4 + 1];
        prior.ppx = intrinsics[i * 4 + 2];
        prior.ppy = intrinsics[i * 4 + 3];
        prior.rotation = cv::Mat::eye(3, 3, CV_64F);
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 3; ++col) {
                prior.rotation.at<double>(row, col) = rotations[i * 9 + row * 3 + col];
            }
        }
        priors[i] = prior;
    }
    return priors;
}

int run_opencv_stitcher(
        const std::vector<std::string> &paths,
        const cv::UMat &matching_mask,
        const std::vector<CameraPrior> &camera_priors,
        const std::string &output_path) {
    if (paths.size() < 3) {
        return STATUS_NEED_MORE_IMAGES;
    }

    std::vector<cv::Mat> images;
    images.reserve(paths.size());
    for (const auto &path: paths) {
        cv::Mat image = cv::imread(path, cv::IMREAD_COLOR);
        if (image.empty()) {
            return STATUS_IO_FAILED;
        }
        images.push_back(image);
    }

    cv::Ptr<cv::Stitcher> stitcher = cv::Stitcher::create(cv::Stitcher::PANORAMA);
    stitcher->setRegistrationResol(WORK_MEGAPIX);
    stitcher->setSeamEstimationResol(SEAM_MEGAPIX);
    stitcher->setCompositingResol(COMPOSE_MEGAPIX);
    stitcher->setPanoConfidenceThresh(PANORAMA_CONFIDENCE);
    stitcher->setWaveCorrection(true);
    stitcher->setWaveCorrectKind(cv::detail::WAVE_CORRECT_HORIZ);
    stitcher->setFeaturesFinder(cv::ORB::create(5000));
    stitcher->setFeaturesMatcher(cv::makePtr<cv::detail::BestOf2NearestMatcher>(false, 0.3f));
    stitcher->setEstimator(cv::makePtr<SensorPriorEstimator>(camera_priors));
    stitcher->setBundleAdjuster(cv::makePtr<cv::detail::BundleAdjusterRay>());
    if (!matching_mask.empty()) {
        stitcher->setMatchingMask(matching_mask);
    }
    stitcher->setWarper(cv::makePtr<cv::SphericalWarper>());
    stitcher->setExposureCompensator(
            cv::detail::ExposureCompensator::createDefault(
                    cv::detail::ExposureCompensator::GAIN_BLOCKS));
    stitcher->setSeamFinder(
            cv::makePtr<cv::detail::GraphCutSeamFinder>(
                    cv::detail::GraphCutSeamFinderBase::COST_COLOR_GRAD));
    stitcher->setBlender(cv::detail::Blender::createDefault(cv::detail::Blender::MULTI_BAND, false));

    cv::Mat panorama;
    cv::Stitcher::Status status = stitcher->stitch(images, panorama);
    if (status != cv::Stitcher::OK) {
        return static_cast<int>(status);
    }
    if (panorama.empty() || !cv::imwrite(output_path, panorama)) {
        return STATUS_IO_FAILED;
    }
    return STATUS_OK;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_spherify_app_NativeOpenCvStitcher_stitchPanoramaNative(
        JNIEnv *env,
        jclass,
        jobjectArray input_paths,
        jintArray matching_mask,
        jdoubleArray camera_intrinsics,
        jdoubleArray camera_rotations,
        jintArray camera_prior_available,
        jstring output_path) {
    if (input_paths == nullptr || output_path == nullptr) {
        return STATUS_NEED_MORE_IMAGES;
    }
    std::vector<std::string> paths = to_paths(env, input_paths);
    cv::UMat mask = to_matching_mask(env, matching_mask, paths.size());
    std::vector<CameraPrior> camera_priors = to_camera_priors(
            env,
            camera_intrinsics,
            camera_rotations,
            camera_prior_available,
            paths.size());
    std::string output = jstring_to_string(env, output_path);
    try {
        return run_opencv_stitcher(paths, mask, camera_priors, output);
    } catch (const cv::Exception &) {
        return STATUS_CAMERA_ADJUST_FAILED;
    } catch (...) {
        return STATUS_IO_FAILED;
    }
}
