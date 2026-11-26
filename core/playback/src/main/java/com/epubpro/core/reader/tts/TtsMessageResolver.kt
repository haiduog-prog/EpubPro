package com.epubpro.core.reader.tts

import android.content.Context
import androidx.annotation.StringRes
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * Nguồn chuỗi hiển thị cho các engine TTS không giữ [Context] (Piper, VieNeu), để thông báo lỗi
 * gửi lên notification, bubble và Reader luôn lấy từ `strings.xml` thay vì viết cứng trong code.
 *
 * Implementation phải trả về chuỗi đã định dạng sẵn, an toàn gọi từ bất kỳ thread nào.
 */
interface TtsMessageResolver {
    /**
     * Lấy chuỗi đã bản địa hoá theo resource ID.
     *
     * @param resId ID chuỗi trong `core/designsystem/src/main/res/values/strings.xml`.
     * @param formatArgs Tham số định dạng cho placeholder của chuỗi.
     * @return Chuỗi sẵn sàng hiển thị cho người dùng.
     */
    fun getString(@StringRes resId: Int, vararg formatArgs: Any): String
}

/**
 * Lấy chuỗi từ resource của ứng dụng thông qua application [Context].
 *
 * @property context Application context, không giữ tham chiếu tới Activity.
 */
class AndroidTtsMessageResolver @Inject constructor(
    @ApplicationContext private val context: Context
) : TtsMessageResolver {
    /**
     * Đọc chuỗi resource bằng [Context.getString].
     *
     * @param resId ID chuỗi cần đọc.
     * @param formatArgs Tham số định dạng.
     * @return Chuỗi đã định dạng.
     */
    override fun getString(resId: Int, vararg formatArgs: Any): String =
        context.getString(resId, *formatArgs)
}

/** Khai báo binding Hilt cho [TtsMessageResolver] dùng chung trong module playback. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TtsMessageResolverModule {
    /**
     * Dùng [AndroidTtsMessageResolver] làm implementation mặc định.
     *
     * @param resolver Implementation đọc resource từ application context.
     * @return Binding kiểu [TtsMessageResolver].
     */
    @Binds
    abstract fun bindTtsMessageResolver(resolver: AndroidTtsMessageResolver): TtsMessageResolver
}
