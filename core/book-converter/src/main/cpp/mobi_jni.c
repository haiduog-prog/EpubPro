#include <jni.h>
#include <mobi.h>

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

enum {
    CONVERTER_OK = 0,
    CONVERTER_IO_ERROR = 100,
    CONVERTER_INVALID_ARGUMENT = 101,
    CONVERTER_UNSUPPORTED = 102
};

#define MAX_OUTPUT_BYTES (500ULL * 1024ULL * 1024ULL)

static int make_directory(const char *path) {
    if (mkdir(path, 0700) == 0 || errno == EEXIST) {
        return 0;
    }
    return -1;
}

static int join_path(char *destination, size_t capacity, const char *directory, const char *name) {
    const int written = snprintf(destination, capacity, "%s/%s", directory, name);
    return written > 0 && (size_t) written < capacity ? 0 : -1;
}

static int write_part(const char *directory, const MOBIPart *part, const char *prefix) {
    if (part->size > 0 && part->data == NULL) {
        return CONVERTER_IO_ERROR;
    }
    char path[PATH_MAX];
    const MOBIFileMeta metadata = mobi_get_filemeta_by_type(part->type);
    char name[64];
    const int name_length = snprintf(name, sizeof(name), "%s%05zu.%s", prefix, part->uid, metadata.extension);
    if (name_length <= 0 || (size_t) name_length >= sizeof(name) || join_path(path, sizeof(path), directory, name) != 0) {
        return CONVERTER_IO_ERROR;
    }

    FILE *file = fopen(path, "wb");
    if (file == NULL) {
        return CONVERTER_IO_ERROR;
    }
    const size_t written = part->size > 0 ? fwrite(part->data, 1, part->size, file) : 0;
    const int close_result = fclose(file);
    return written == part->size && close_result == 0 ? CONVERTER_OK : CONVERTER_IO_ERROR;
}

static int write_resource(const char *directory, const MOBIPart *part) {
    if (part->size > 0 && part->data == NULL) {
        return CONVERTER_IO_ERROR;
    }
    char path[PATH_MAX];
    char name[64];
    const MOBIFileMeta metadata = mobi_get_filemeta_by_type(part->type);
    if (metadata.type == T_OPF) {
        if (join_path(path, sizeof(path), directory, "content.opf") != 0) {
            return CONVERTER_IO_ERROR;
        }
    } else {
        const int name_length = snprintf(name, sizeof(name), "resource%05zu.%s", part->uid, metadata.extension);
        if (name_length <= 0 || (size_t) name_length >= sizeof(name) || join_path(path, sizeof(path), directory, name) != 0) {
            return CONVERTER_IO_ERROR;
        }
    }

    FILE *file = fopen(path, "wb");
    if (file == NULL) {
        return CONVERTER_IO_ERROR;
    }
    const size_t written = part->size > 0 ? fwrite(part->data, 1, part->size, file) : 0;
    const int close_result = fclose(file);
    return written == part->size && close_result == 0 ? CONVERTER_OK : CONVERTER_IO_ERROR;
}

static int write_rawml(const MOBIRawml *rawml, const char *directory) {
    unsigned long long total_written = 0;
    const MOBIPart *part = rawml->markup;
    while (part != NULL) {
        if (part->size > MAX_OUTPUT_BYTES - total_written) {
            return CONVERTER_IO_ERROR;
        }
        if (write_part(directory, part, "part") != CONVERTER_OK) {
            return CONVERTER_IO_ERROR;
        }
        total_written += part->size;
        part = part->next;
    }

    /* Phần tử đầu tiên của flow là raw HTML tổng hợp, không phải tài nguyên riêng. */
    part = rawml->flow == NULL ? NULL : rawml->flow->next;
    while (part != NULL) {
        if (part->size > MAX_OUTPUT_BYTES - total_written) {
            return CONVERTER_IO_ERROR;
        }
        if (write_part(directory, part, "flow") != CONVERTER_OK) {
            return CONVERTER_IO_ERROR;
        }
        total_written += part->size;
        part = part->next;
    }

    part = rawml->resources;
    while (part != NULL) {
        if (part->size > 0) {
            if (part->size > MAX_OUTPUT_BYTES - total_written) {
                return CONVERTER_IO_ERROR;
            }
            if (write_resource(directory, part) != CONVERTER_OK) {
                return CONVERTER_IO_ERROR;
            }
            total_written += part->size;
        }
        part = part->next;
    }
    return CONVERTER_OK;
}

JNIEXPORT jint JNICALL
Java_com_epubpro_core_bookconverter_MobiNativeDecoder_decodeToDirectory(
        JNIEnv *env,
        jclass clazz,
        jstring input_path,
        jstring output_directory) {
    (void) clazz;
    if (input_path == NULL || output_directory == NULL) {
        return CONVERTER_INVALID_ARGUMENT;
    }

    const char *input = (*env)->GetStringUTFChars(env, input_path, NULL);
    if (input == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return CONVERTER_INVALID_ARGUMENT;
    }
    const char *output = (*env)->GetStringUTFChars(env, output_directory, NULL);
    if (output == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->ReleaseStringUTFChars(env, input_path, input);
        return CONVERTER_INVALID_ARGUMENT;
    }

    int result = CONVERTER_OK;
    if (make_directory(output) != 0) {
        result = CONVERTER_IO_ERROR;
        goto cleanup;
    }

    MOBIData *mobi = mobi_init();
    if (mobi == NULL) {
        result = MOBI_MALLOC_FAILED;
        goto cleanup;
    }

    const MOBI_RET load_result = mobi_load_filename(mobi, input);
    if (load_result != MOBI_SUCCESS) {
        result = load_result;
        mobi_free(mobi);
        goto cleanup;
    }
    if (mobi_is_encrypted(mobi)) {
        result = MOBI_FILE_ENCRYPTED;
        mobi_free(mobi);
        goto cleanup;
    }
    if ((!mobi_is_mobipocket(mobi) && !mobi_is_textread(mobi)) || mobi_is_replica(mobi)) {
        result = CONVERTER_UNSUPPORTED;
        mobi_free(mobi);
        goto cleanup;
    }

    MOBIRawml *rawml = mobi_init_rawml(mobi);
    if (rawml == NULL) {
        result = MOBI_MALLOC_FAILED;
        mobi_free(mobi);
        goto cleanup;
    }

    /* Luôn chọn KF8 nếu có và tái dựng liên kết/OPF trước khi trả dữ liệu cho Kotlin. */
    MOBI_RET parse_result = mobi_parse_rawml_opt(rawml, mobi, true, false, true);
    if (parse_result != MOBI_SUCCESS) {
        /* Một số file cũ có NCX/INDX lỗi nhưng phần văn bản vẫn đọc được. */
        mobi_free_rawml(rawml);
        rawml = mobi_init_rawml(mobi);
        if (rawml == NULL) {
            parse_result = MOBI_MALLOC_FAILED;
        } else {
            parse_result = mobi_parse_rawml_opt(rawml, mobi, false, false, true);
        }
    }
    if (parse_result == MOBI_SUCCESS) {
        result = write_rawml(rawml, output);
    } else {
        result = parse_result;
    }

    if (rawml != NULL) {
        mobi_free_rawml(rawml);
    }
    mobi_free(mobi);

cleanup:
    (*env)->ReleaseStringUTFChars(env, input_path, input);
    (*env)->ReleaseStringUTFChars(env, output_directory, output);
    return result;
}
