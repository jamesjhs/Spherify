/*
 * spherify_stitcher.cpp
 *
 * Native PhotoSphere solver/compositor for Spherify. This intentionally uses
 * OpenCV's detail pipeline rather than cv::Stitcher so the app can reason about
 * the same industrial stages described in the research: features, robust
 * matching, camera estimation, bundle adjustment, spherical warping, exposure
 * compensation, graph-cut seams, and multiband blending.
 */
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching/detail/blenders.hpp>
#include <opencv2/stitching/detail/camera.hpp>
#include <opencv2/stitching/detail/exposure_compensate.hpp>
#include <opencv2/stitching/detail/matchers.hpp>
#include <opencv2/stitching/detail/motion_estimators.hpp>
#include <opencv2/stitching/detail/seam_finders.hpp>
#include <opencv2/stitching/detail/util.hpp>
#include <opencv2/stitching/detail/warpers.hpp>
#include <opencv2/stitching/warpers.hpp>

namespace {

constexpr int STATUS_OK = 0;
constexpr int STATUS_NEED_MORE_IMAGES = 1;
constexpr int STATUS_HOMOGRAPHY_FAILED = 2;
constexpr int STATUS_CAMERA_ADJUST_FAILED = 3;
constexpr int STATUS_IO_FAILED = -10;
constexpr int STATUS_FEATURES_FAILED = -11;
constexpr int STATUS_WARP_FAILED = -12;
constexpr int STATUS_BLEND_FAILED = -13;

constexpr double WORK_MEGAPIX = 0.6;
constexpr double SEAM_MEGAPIX = 0.12;
constexpr double COMPOSE_MEGAPIX = -1.0;
constexpr float MATCH_CONFIDENCE = 0.30f;
constexpr float PANORAMA_CONFIDENCE = 1.0f;
constexpr float BLEND_STRENGTH = 5.0f;

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

double registration_scale_for(const cv::Size &size, bool &scale_set, double &work_scale) {
    if (!scale_set) {
        work_scale = std::min(1.0, std::sqrt(WORK_MEGAPIX * 1e6 / size.area()));
        scale_set = true;
    }
    return work_scale;
}

double seam_scale_for(const cv::Size &size, bool &scale_set, double &seam_scale) {
    if (!scale_set) {
        seam_scale = std::min(1.0, std::sqrt(SEAM_MEGAPIX * 1e6 / size.area()));
        scale_set = true;
    }
    return seam_scale;
}

float median_focal(const std::vector<cv::detail::CameraParams> &cameras) {
    std::vector<double> focals;
    focals.reserve(cameras.size());
    for (const auto &camera: cameras) {
        focals.push_back(camera.focal);
    }
    std::sort(focals.begin(), focals.end());
    if (focals.empty()) {
        return 1.0f;
    }
    const size_t mid = focals.size() / 2;
    if (focals.size() % 2 == 1) {
        return static_cast<float>(focals[mid]);
    }
    return static_cast<float>((focals[mid - 1] + focals[mid]) * 0.5);
}

void apply_bundle_refinement_mask(const cv::Ptr<cv::detail::BundleAdjusterBase> &adjuster) {
    cv::Mat_<uchar> refine_mask = cv::Mat::zeros(3, 3, CV_8U);
    refine_mask(0, 0) = 1; // focal length
    refine_mask(0, 1) = 1; // skew
    refine_mask(0, 2) = 1; // principal point x
    refine_mask(1, 1) = 1; // aspect
    refine_mask(1, 2) = 1; // principal point y
    adjuster->setRefinementMask(refine_mask);
}

int run_detail_pipeline(const std::vector<std::string> &paths, const std::string &output_path) {
    const int source_count = static_cast<int>(paths.size());
    if (source_count < 3) {
        return STATUS_NEED_MORE_IMAGES;
    }

    std::vector<cv::Size> full_sizes(source_count);
    std::vector<cv::Mat> seam_images(source_count);
    std::vector<cv::detail::ImageFeatures> features(source_count);

    bool work_scale_set = false;
    bool seam_scale_set = false;
    double work_scale = 1.0;
    double seam_scale = 1.0;
    double seam_work_aspect = 1.0;
    cv::Ptr<cv::Feature2D> finder = cv::ORB::create(5000);

    // Registration and seam work happen at reduced resolution to keep phone
    // memory pressure bounded while preserving full-resolution compositing.
    for (int i = 0; i < source_count; ++i) {
        cv::Mat full = cv::imread(paths[i], cv::IMREAD_COLOR);
        if (full.empty()) {
            return STATUS_IO_FAILED;
        }
        full_sizes[i] = full.size();
        registration_scale_for(full.size(), work_scale_set, work_scale);
        seam_scale_for(full.size(), seam_scale_set, seam_scale);
        seam_work_aspect = seam_scale / work_scale;

        cv::Mat work;
        cv::resize(full, work, cv::Size(), work_scale, work_scale, cv::INTER_LINEAR_EXACT);
        cv::detail::computeImageFeatures(finder, work, features[i]);
        features[i].img_idx = i;
        if (features[i].keypoints.empty()) {
            return STATUS_FEATURES_FAILED;
        }

        cv::resize(full, seam_images[i], cv::Size(), seam_scale, seam_scale, cv::INTER_LINEAR_EXACT);
    }

    // ORB plus BestOf2Nearest gives a robust, patent-safe baseline for Android.
    std::vector<cv::detail::MatchesInfo> pairwise_matches;
    cv::Ptr<cv::detail::FeaturesMatcher> matcher =
            cv::makePtr<cv::detail::BestOf2NearestMatcher>(false, MATCH_CONFIDENCE);
    (*matcher)(features, pairwise_matches);
    matcher->collectGarbage();

    std::vector<int> indices = cv::detail::leaveBiggestComponent(
            features,
            pairwise_matches,
            PANORAMA_CONFIDENCE);
    if (indices.size() < 3) {
        return STATUS_NEED_MORE_IMAGES;
    }

    std::vector<std::string> paths_subset;
    std::vector<cv::Size> full_sizes_subset;
    std::vector<cv::Mat> seam_images_subset;
    std::vector<cv::detail::ImageFeatures> features_subset;
    paths_subset.reserve(indices.size());
    full_sizes_subset.reserve(indices.size());
    seam_images_subset.reserve(indices.size());
    features_subset.reserve(indices.size());
    for (int index: indices) {
        paths_subset.push_back(paths[index]);
        full_sizes_subset.push_back(full_sizes[index]);
        seam_images_subset.push_back(seam_images[index]);
        features_subset.push_back(features[index]);
    }
    full_sizes.swap(full_sizes_subset);
    seam_images.swap(seam_images_subset);
    features.swap(features_subset);
    const int image_count = static_cast<int>(features.size());

    // Estimate camera rotations, then refine them globally with ray bundle
    // adjustment. Sensor pose remains a capture prior in Java; visual
    // registration owns final geometry here.
    cv::Ptr<cv::detail::Estimator> estimator = cv::makePtr<cv::detail::HomographyBasedEstimator>();
    std::vector<cv::detail::CameraParams> cameras;
    if (!(*estimator)(features, pairwise_matches, cameras)) {
        return STATUS_HOMOGRAPHY_FAILED;
    }
    for (auto &camera: cameras) {
        cv::Mat rotation;
        camera.R.convertTo(rotation, CV_32F);
        camera.R = rotation;
    }

    cv::Ptr<cv::detail::BundleAdjusterBase> adjuster =
            cv::makePtr<cv::detail::BundleAdjusterRay>();
    adjuster->setConfThresh(PANORAMA_CONFIDENCE);
    apply_bundle_refinement_mask(adjuster);
    if (!(*adjuster)(features, pairwise_matches, cameras)) {
        return STATUS_CAMERA_ADJUST_FAILED;
    }

    std::vector<cv::Mat> rotations;
    rotations.reserve(cameras.size());
    for (const auto &camera: cameras) {
        rotations.push_back(camera.R.clone());
    }
    cv::detail::waveCorrect(rotations, cv::detail::WAVE_CORRECT_HORIZ);
    for (size_t i = 0; i < cameras.size(); ++i) {
        cameras[i].R = rotations[i];
    }

    // Spherical warping is the required projection family for PhotoSphere-like
    // output. Java later rejects candidates that are not exact GPano 2:1.
    float warped_image_scale = median_focal(cameras);
    cv::Ptr<cv::WarperCreator> warper_creator = cv::makePtr<cv::SphericalWarper>();
    cv::Ptr<cv::detail::RotationWarper> warper =
            warper_creator->create(static_cast<float>(warped_image_scale * seam_work_aspect));

    std::vector<cv::Point> corners(image_count);
    std::vector<cv::UMat> masks(image_count);
    std::vector<cv::UMat> masks_warped(image_count);
    std::vector<cv::UMat> images_warped(image_count);
    std::vector<cv::Size> sizes(image_count);

    for (int i = 0; i < image_count; ++i) {
        masks[i].create(seam_images[i].size(), CV_8U);
        masks[i].setTo(cv::Scalar::all(255));

        cv::Mat_<float> K;
        cameras[i].K().convertTo(K, CV_32F);
        const float scale = static_cast<float>(seam_work_aspect);
        K(0, 0) *= scale;
        K(0, 2) *= scale;
        K(1, 1) *= scale;
        K(1, 2) *= scale;

        corners[i] = warper->warp(seam_images[i], K, cameras[i].R, cv::INTER_LINEAR,
                                  cv::BORDER_REFLECT, images_warped[i]);
        sizes[i] = images_warped[i].size();
        if (sizes[i].empty()) {
            return STATUS_WARP_FAILED;
        }
        warper->warp(masks[i], K, cameras[i].R, cv::INTER_NEAREST,
                     cv::BORDER_CONSTANT, masks_warped[i]);
    }

    std::vector<cv::UMat> images_warped_float(image_count);
    for (int i = 0; i < image_count; ++i) {
        images_warped[i].convertTo(images_warped_float[i], CV_32F);
    }

    // Block gain compensation estimates photometric correction from overlaps
    // before seam selection and blending.
    cv::Ptr<cv::detail::ExposureCompensator> compensator =
            cv::detail::ExposureCompensator::createDefault(
                    cv::detail::ExposureCompensator::GAIN_BLOCKS);
    if (auto *blocks = dynamic_cast<cv::detail::BlocksCompensator *>(compensator.get())) {
        blocks->setNrFeeds(1);
        blocks->setNrGainsFilteringIterations(2);
        blocks->setBlockSize(32, 32);
    }
    compensator->feed(corners, images_warped, masks_warped);

    // Graph-cut seam masks are selected before the final full-resolution
    // multiband blend, matching the literature order of operations.
    cv::Ptr<cv::detail::SeamFinder> seam_finder =
            cv::makePtr<cv::detail::GraphCutSeamFinder>(
                    cv::detail::GraphCutSeamFinderBase::COST_COLOR_GRAD);
    seam_finder->find(images_warped_float, corners, masks_warped);

    seam_images.clear();
    images_warped.clear();
    images_warped_float.clear();
    masks.clear();

    bool compose_scale_set = false;
    double compose_scale = 1.0;
    double compose_work_aspect = 1.0;
    cv::Ptr<cv::detail::Blender> blender;

    // Re-read source images for composition so only one full-resolution frame
    // is resident at a time.
    for (int image_index = 0; image_index < image_count; ++image_index) {
        cv::Mat full = cv::imread(paths_subset[image_index], cv::IMREAD_COLOR);
        if (full.empty()) {
            return STATUS_IO_FAILED;
        }

        if (!compose_scale_set) {
            if (COMPOSE_MEGAPIX > 0) {
                compose_scale = std::min(1.0, std::sqrt(COMPOSE_MEGAPIX * 1e6 / full.size().area()));
            }
            compose_work_aspect = compose_scale / work_scale;
            warped_image_scale *= static_cast<float>(compose_work_aspect);
            warper = warper_creator->create(warped_image_scale);
            for (int i = 0; i < image_count; ++i) {
                cameras[i].focal *= compose_work_aspect;
                cameras[i].ppx *= compose_work_aspect;
                cameras[i].ppy *= compose_work_aspect;

                cv::Size size = full_sizes[i];
                if (std::abs(compose_scale - 1.0) > 1e-1) {
                    size.width = cvRound(full_sizes[i].width * compose_scale);
                    size.height = cvRound(full_sizes[i].height * compose_scale);
                }
                cv::Mat K;
                cameras[i].K().convertTo(K, CV_32F);
                cv::Rect roi = warper->warpRoi(size, K, cameras[i].R);
                corners[i] = roi.tl();
                sizes[i] = roi.size();
            }
            compose_scale_set = true;
        }

        cv::Mat image;
        if (std::abs(compose_scale - 1.0) > 1e-1) {
            cv::resize(full, image, cv::Size(), compose_scale, compose_scale, cv::INTER_LINEAR_EXACT);
        } else {
            image = full;
        }

        cv::Mat K;
        cameras[image_index].K().convertTo(K, CV_32F);
        cv::Mat image_warped;
        cv::Mat mask_warped;
        cv::Mat mask(image.size(), CV_8U, cv::Scalar::all(255));
        warper->warp(image, K, cameras[image_index].R, cv::INTER_LINEAR,
                     cv::BORDER_REFLECT, image_warped);
        warper->warp(mask, K, cameras[image_index].R, cv::INTER_NEAREST,
                     cv::BORDER_CONSTANT, mask_warped);
        compensator->apply(image_index, corners[image_index], image_warped, mask_warped);

        cv::Mat image_warped_s;
        image_warped.convertTo(image_warped_s, CV_16S);

        cv::Mat dilated_mask;
        cv::Mat seam_mask;
        cv::dilate(masks_warped[image_index].getMat(cv::ACCESS_READ), dilated_mask, cv::Mat());
        cv::resize(dilated_mask, seam_mask, mask_warped.size(), 0, 0, cv::INTER_LINEAR_EXACT);
        mask_warped = seam_mask & mask_warped;

        if (!blender) {
            blender = cv::detail::Blender::createDefault(cv::detail::Blender::MULTI_BAND, false);
            cv::Size destination_size = cv::detail::resultRoi(corners, sizes).size();
            const float blend_width =
                    std::sqrt(static_cast<float>(destination_size.area())) * BLEND_STRENGTH / 100.0f;
            if (blend_width < 1.0f) {
                blender = cv::detail::Blender::createDefault(cv::detail::Blender::NO, false);
            } else if (auto *multi = dynamic_cast<cv::detail::MultiBandBlender *>(blender.get())) {
                multi->setNumBands(static_cast<int>(std::ceil(std::log(blend_width) / std::log(2.0)) - 1.0));
            }
            blender->prepare(corners, sizes);
        }
        blender->feed(image_warped_s, mask_warped, corners[image_index]);
    }

    cv::Mat result;
    cv::Mat result_mask;
    blender->blend(result, result_mask);
    if (result.empty()) {
        return STATUS_BLEND_FAILED;
    }

    cv::Mat result_8u;
    result.convertTo(result_8u, CV_8U);
    if (!cv::imwrite(output_path, result_8u)) {
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
        return run_detail_pipeline(paths, output);
    } catch (const cv::Exception &) {
        return STATUS_CAMERA_ADJUST_FAILED;
    } catch (...) {
        return STATUS_IO_FAILED;
    }
}
