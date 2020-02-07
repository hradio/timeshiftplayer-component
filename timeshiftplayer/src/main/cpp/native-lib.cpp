#include <jni.h>
#include <android/log.h>

extern "C" {

#include <libavformat/avformat.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>

JavaVM* m_javaVm;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    __android_log_print(ANDROID_LOG_DEBUG, "FFmpeg", "Huhu");
    JNIEnv *env;
    vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    env->GetJavaVM(&m_javaVm);

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_eu_hradio_timeshiftplayer_PcmResampler_info(JNIEnv* env, jobject thiz) {
    __android_log_print(ANDROID_LOG_DEBUG, "FFmpeg", "%s", avcodec_configuration());
}

struct SwrContext *swr_ctx;
int src_rate;
int dst_rate;
int src_nb_channels;
JNIEXPORT jint JNICALL Java_eu_hradio_timeshiftplayer_PcmResampler_init(JNIEnv* env, jobject thiz, jint srcRate, jint dstRate, jint srcChans) {

    uint8_t **src_data = NULL;
    uint8_t **dst_data = NULL;

    int src_linesize;
    int dst_linesize;

    swr_ctx = swr_alloc();

    int64_t src_ch_layout;
    if(srcChans == 1) {
        src_ch_layout = AV_CH_LAYOUT_MONO;
    } else {
        src_ch_layout = AV_CH_LAYOUT_STEREO;
    }
    int64_t dst_ch_layout = AV_CH_LAYOUT_STEREO;

    src_rate = srcRate;
    dst_rate = dstRate;

    src_nb_channels = srcChans;
    int dst_nb_channels = 0;

    enum AVSampleFormat src_sample_fmt = AV_SAMPLE_FMT_S16;
    enum AVSampleFormat dst_sample_fmt = AV_SAMPLE_FMT_S16;

    av_opt_set_int(swr_ctx, "in_channel_layout",    src_ch_layout, 0);
    av_opt_set_int(swr_ctx, "in_sample_rate",       src_rate, 0);
    av_opt_set_sample_fmt(swr_ctx, "in_sample_fmt", src_sample_fmt, 0);

    av_opt_set_int(swr_ctx, "out_channel_layout",    dst_ch_layout, 0);
    av_opt_set_int(swr_ctx, "out_sample_rate",       dst_rate, 0);
    av_opt_set_sample_fmt(swr_ctx, "out_sample_fmt", dst_sample_fmt, 0);

    int ret = -1;
    ret = swr_init(swr_ctx);
    __android_log_print(ANDROID_LOG_DEBUG, "FFmpeg", "swr_init %d", ret);

    return ret;
}

JNIEXPORT void JNICALL Java_eu_hradio_timeshiftplayer_PcmResampler_deInit(JNIEnv* env, jobject thiz) {
    __android_log_print(ANDROID_LOG_DEBUG, "FFmpeg", "Resampler native deInit");
    swr_free(&swr_ctx);
}

JNIEXPORT jbyteArray JNICALL Java_eu_hradio_timeshiftplayer_PcmResampler_resample(JNIEnv* env, jobject thiz, jbyteArray pcmData, jint pcmDataLen) {

    const u_int8_t* in_samples = new u_int8_t[pcmDataLen];
    env->GetByteArrayRegion(pcmData, 0, pcmDataLen, (jbyte*)in_samples);

    int in_num_samples = pcmDataLen / 2 / src_nb_channels;
    uint8_t* out_samples;
    int out_num_samples = av_rescale_rnd(swr_get_delay(swr_ctx, src_rate) + in_num_samples, dst_rate, src_rate, AV_ROUND_UP);
    av_samples_alloc(&out_samples, nullptr, 2, out_num_samples, AV_SAMPLE_FMT_S16, 0);
    out_num_samples = swr_convert(swr_ctx, &out_samples, out_num_samples, &in_samples, in_num_samples);
    //__android_log_print(ANDROID_LOG_DEBUG, "FFmpeg", "Resampled %d", out_num_samples);

    int sampleDataSize = out_num_samples*4;
    jbyteArray decData = env->NewByteArray(sampleDataSize);
    env->SetByteArrayRegion (decData, 0, sampleDataSize, (jbyte*)out_samples);
    av_freep(&out_samples);

    return decData;
}

}