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
#include <opencv2/stitching/detail/exposure_compensate.hpp>
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

int run_opencv_stitcher(const std::vector<std::string> &paths, const std::string &output_path) {
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
        jstring output_path) {
    if (input_paths == nullptr || output_path == nullptr) {
        return STATUS_NEED_MORE_IMAGES;
    }
    std::vector<std::string> paths = to_paths(env, input_paths);
    std::string output = jstring_to_string(env, output_path);
    try {
        return run_opencv_stitcher(paths, output);
    } catch (const cv::Exception &) {
        return STATUS_CAMERA_ADJUST_FAILED;
    } catch (...) {
        return STATUS_IO_FAILED;
    }
}
