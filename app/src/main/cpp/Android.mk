# L'encoder MP3: LAME 3.100 (LGPL, sorgenti in lame/, scaricati da sourceforge con sha256
# ddfe36ca…1da1e) + il ponte mp3.c. Solo quello che serve per codificare: niente decoder, niente frontend.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := mp3
LOCAL_SRC_FILES := mp3.c $(addprefix lame/, bitstream.c encoder.c fft.c gain_analysis.c id3tag.c lame.c \
    mpglib_interface.c newmdct.c presets.c psymodel.c quantize.c quantize_pvt.c reservoir.c set_get.c \
    tables.c takehiro.c util.c vbrquantize.c VbrTag.c version.c)
LOCAL_C_INCLUDES := $(LOCAL_PATH)/lame
LOCAL_CFLAGS := -O2 -DSTDC_HEADERS -DHAVE_STDINT_H -DHAVE_LIMITS_H -Dieee754_float32_t=float -w
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
