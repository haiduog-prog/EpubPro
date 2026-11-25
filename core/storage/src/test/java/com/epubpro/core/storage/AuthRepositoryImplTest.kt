package com.epubpro.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.epubpro.core.storage.network.*
import com.epubpro.domain.model.AuthProvider
import com.epubpro.domain.model.AuthState
import com.epubpro.domain.model.User
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import retrofit2.HttpException
import retrofit2.Response

/**
 * Kiểm thử đơn vị cho [AuthRepositoryImpl] với cấu hình Backend và Supabase Auth.
 */
class AuthRepositoryImplTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockServerPreferencesManager: ServerPreferencesManager
    private lateinit var mockBackendAuthApiService: BackendAuthApiService
    private lateinit var mockSupabaseAuthApiService: SupabaseAuthApiService
    private val gson = Gson()

    private val storedData = mutableMapOf<String, Any?>()

    private lateinit var authPreferencesManager: AuthPreferencesManager
    private lateinit var authRepository: AuthRepositoryImpl

    private val supabaseConfig = AuthConfigDto(
        mode = "supabase",
        authRequired = true,
        supabaseUrl = "https://mockproject.supabase.co",
        supabasePublishableKey = "mock_publishable_key_123"
    )

    private val localConfig = AuthConfigDto(
        mode = "local",
        authRequired = false,
        supabaseUrl = null,
        supabasePublishableKey = null
    )

    @Before
    fun setUp() {
        mockContext = mock()
        mockPrefs = mock()
        mockEditor = mock()
        mockServerPreferencesManager = mock()
        mockBackendAuthApiService = mock()
        mockSupabaseAuthApiService = mock()
        storedData.clear()

        whenever(mockServerPreferencesManager.getBaseUrl()).thenReturn("https://epubbackend.onrender.com/api/v1/")

        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockPrefs)

        whenever(mockPrefs.getString(any(), anyOrNull())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val default = invocation.arguments[1] as? String
            (storedData[key] as? String) ?: default
        }

        whenever(mockPrefs.getBoolean(any(), any())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val default = invocation.arguments[1] as Boolean
            (storedData[key] as? Boolean) ?: default
        }

        whenever(mockPrefs.getLong(any(), any())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val default = invocation.arguments[1] as Long
            (storedData[key] as? Long) ?: default
        }

        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), anyOrNull())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val value = invocation.arguments[1] as? String
            storedData[key] = value
            mockEditor
        }
        whenever(mockEditor.putBoolean(any(), any())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val value = invocation.arguments[1] as Boolean
            storedData[key] = value
            mockEditor
        }
        whenever(mockEditor.putLong(any(), any())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val value = invocation.arguments[1] as Long
            storedData[key] = value
            mockEditor
        }
        whenever(mockEditor.remove(any())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            storedData.remove(key)
            mockEditor
        }

        authPreferencesManager = AuthPreferencesManager(mockContext, gson)
        authRepository = AuthRepositoryImpl(
            authPreferencesManager = authPreferencesManager,
            serverPreferencesManager = mockServerPreferencesManager,
            backendAuthApiService = mockBackendAuthApiService,
            supabaseAuthApiService = mockSupabaseAuthApiService,
            gson = gson
        )

        runBlocking {
            whenever(mockBackendAuthApiService.getAuthConfigFromUrl(any(), any())).thenReturn(supabaseConfig)
            whenever(mockBackendAuthApiService.getAuthConfig()).thenReturn(supabaseConfig)
        }
    }

    @Test
    fun testInitialStateIsUnauthenticated(): Unit = runBlocking {
        val state = authRepository.authState.first()
        assertTrue("Trạng thái khởi tạo phải là Unauthenticated", state is AuthState.Unauthenticated)
        assertNull("Không có user khi chưa đăng nhập", authRepository.getCurrentUser())
    }

    @Test
    fun testLoginWithSupabaseSuccess(): Unit = runBlocking {

        val tokenResponse = SupabaseTokenResponseDto(
            accessToken = "supabase_access_token_xyz",
            refreshToken = "supabase_refresh_token_123",
            expiresIn = 3600,
            expiresAt = 1756030000L,
            user = SupabaseUserDto(
                id = "sb-user-id-001",
                email = "reader@epubpro.app",
                role = "authenticated"
            )
        )
        whenever(
            mockSupabaseAuthApiService.loginWithPassword(
                url = eq("https://mockproject.supabase.co/auth/v1/token?grant_type=password"),
                apiKey = eq("mock_publishable_key_123"),
                body = eq(SupabaseLoginRequestDto("reader@epubpro.app", "secret123"))
            )
        ).thenReturn(tokenResponse)

        val result = authRepository.login("reader@epubpro.app", "secret123")
        assertTrue("Đăng nhập Supabase thành công", result.isSuccess)

        val user = result.getOrNull()
        assertNotNull(user)
        assertEquals("sb-user-id-001", user?.id)
        assertEquals("reader@epubpro.app", user?.email)
        assertEquals("supabase_access_token_xyz", user?.token)
        assertEquals("supabase_refresh_token_123", user?.refreshToken)

        assertEquals("supabase_access_token_xyz", authPreferencesManager.getAuthToken())
        assertEquals("supabase_refresh_token_123", authPreferencesManager.getRefreshToken())

        val currentState = authRepository.authState.first()
        assertTrue(currentState is AuthState.Authenticated)
    }

    @Test
    fun testLoginWithSupabaseInvalidCredentials(): Unit = runBlocking {
        whenever(mockBackendAuthApiService.getAuthConfig()).thenReturn(supabaseConfig)

        val errorJson = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid login credentials\"}"
        val errorResponseBody = errorJson.toResponseBody("application/json".toMediaTypeOrNull())
        val httpException = HttpException(Response.error<ResponseBody>(400, errorResponseBody))

        whenever(
            mockSupabaseAuthApiService.loginWithPassword(any(), any(), any())
        ).thenThrow(httpException)

        val result = authRepository.login("reader@epubpro.app", "wrong_password")
        assertTrue("Đăng nhập phải thất bại khi sai mật khẩu", result.isFailure)
        val errorMessage = result.exceptionOrNull()?.message
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("Email hoặc mật khẩu không chính xác."))
    }

    @Test
    fun testLoginLocalModeFallback(): Unit = runBlocking {
        whenever(mockBackendAuthApiService.getAuthConfigFromUrl(any(), any())).thenReturn(localConfig)
        whenever(mockBackendAuthApiService.getAuthConfig()).thenReturn(localConfig)

        val result = authRepository.login("localuser@epubpro.app", "secret123")
        assertTrue("Đăng nhập local dev thành công", result.isSuccess)

        val user = result.getOrNull()
        assertNotNull(user)
        assertEquals("localuser@epubpro.app", user?.email)
        assertNotNull(user?.token)
    }

    @Test
    fun testRefreshTokenSuccess(): Unit = runBlocking {
        whenever(mockBackendAuthApiService.getAuthConfig()).thenReturn(supabaseConfig)

        authPreferencesManager.saveUser(
            User(
                id = "user_1",
                email = "user@test.com",
                displayName = "User",
                token = "old_access_token",
                refreshToken = "old_refresh_token"
            )
        )

        val tokenResponse = SupabaseTokenResponseDto(
            accessToken = "new_access_token",
            refreshToken = "new_refresh_token",
            expiresIn = 3600,
            user = SupabaseUserDto(id = "user_1", email = "user@test.com")
        )

        whenever(
            mockSupabaseAuthApiService.refreshToken(
                url = eq("https://mockproject.supabase.co/auth/v1/token?grant_type=refresh_token"),
                apiKey = eq("mock_publishable_key_123"),
                body = eq(SupabaseRefreshTokenRequestDto("old_refresh_token"))
            )
        ).thenReturn(tokenResponse)

        val result = authRepository.refreshToken()
        assertTrue("Làm mới token thành công", result.isSuccess)

        assertEquals("new_access_token", authPreferencesManager.getAuthToken())
        assertEquals("new_refresh_token", authPreferencesManager.getRefreshToken())
    }

    /**
     * Kiểm tra token làm mới về sau khi đã đăng xuất không được ghi lại vào phiên.
     */
    @Test
    fun testRefreshedTokensAreDroppedAfterLogout() {
        authPreferencesManager.saveUser(
            User(
                id = "user_1",
                email = "user@test.com",
                displayName = "User",
                token = "old_access_token",
                refreshToken = "old_refresh_token"
            )
        )
        authPreferencesManager.clearSession()

        val stored = authPreferencesManager.replaceTokensIfCurrent(
            expectedRefreshToken = "old_refresh_token",
            accessToken = "late_access_token",
            refreshToken = "late_refresh_token",
            expiresAt = 1L
        )

        assertFalse(stored)
        assertFalse(authPreferencesManager.saveTokens("late_access_token", "late_refresh_token", 1L))
        assertNull(authPreferencesManager.getAuthToken())
        assertNull(authPreferencesManager.getRefreshToken())
    }

    /**
     * Kiểm tra token làm mới của tài khoản cũ không ghi đè phiên của tài khoản vừa đăng nhập.
     */
    @Test
    fun testRefreshedTokensAreDroppedAfterAccountSwitch() {
        authPreferencesManager.saveUser(
            User(id = "user_1", email = "a@test.com", displayName = "A", token = "a_access", refreshToken = "a_refresh")
        )
        authPreferencesManager.saveUser(
            User(id = "user_2", email = "b@test.com", displayName = "B", token = "b_access", refreshToken = "b_refresh")
        )

        val stored = authPreferencesManager.replaceTokensIfCurrent(
            expectedRefreshToken = "a_refresh",
            accessToken = "a_new_access",
            refreshToken = "a_new_refresh",
            expiresAt = 1L
        )

        assertFalse(stored)
        assertEquals("b_access", authPreferencesManager.getAuthToken())
        assertEquals("b_refresh", authPreferencesManager.getRefreshToken())
        assertTrue(
            authPreferencesManager.replaceTokensIfCurrent("b_refresh", "b_new_access", null, 1L)
        )
        assertEquals("b_new_access", authPreferencesManager.getAuthToken())
        assertEquals("b_refresh", authPreferencesManager.getRefreshToken())
    }

    @Test
    fun testLogoutCallsSupabaseAndClearsSession(): Unit = runBlocking {
        whenever(mockBackendAuthApiService.getAuthConfig()).thenReturn(supabaseConfig)
        authPreferencesManager.saveAuthConfig(supabaseConfig)

        authPreferencesManager.saveUser(
            User(
                id = "user_1",
                email = "user@test.com",
                displayName = "User",
                token = "valid_access_token",
                refreshToken = "valid_refresh_token"
            )
        )

        whenever(
            mockSupabaseAuthApiService.logout(any(), any(), any())
        ).thenReturn(Response.success("".toResponseBody(null)))

        val logoutResult = authRepository.logout()
        assertTrue(logoutResult.isSuccess)

        verify(mockSupabaseAuthApiService).logout(
            url = eq("https://mockproject.supabase.co/auth/v1/logout"),
            apiKey = eq("mock_publishable_key_123"),
            authorization = eq("Bearer valid_access_token")
        )

        val state = authRepository.authState.first()
        assertTrue(state is AuthState.Unauthenticated)
        assertNull(authPreferencesManager.getAuthToken())
        assertNull(authPreferencesManager.getSavedUser())
    }

    @Test
    fun testRegisterWithSupabaseSuccess(): Unit = runBlocking {
        whenever(mockBackendAuthApiService.getAuthConfig()).thenReturn(supabaseConfig)

        val signupResponse = SupabaseSignupResponseDto(
            id = "new_sb_user_id",
            email = "newuser@epubpro.app",
            accessToken = "new_sb_access_token",
            refreshToken = "new_sb_refresh_token",
            expiresIn = 3600,
            user = SupabaseUserDto(id = "new_sb_user_id", email = "newuser@epubpro.app")
        )

        whenever(
            mockSupabaseAuthApiService.signUp(
                url = eq("https://mockproject.supabase.co/auth/v1/signup"),
                apiKey = eq("mock_publishable_key_123"),
                body = any()
            )
        ).thenReturn(signupResponse)

        val result = authRepository.register("newuser@epubpro.app", "secret123", "Nguyễn Văn B")
        assertTrue("Đăng ký thành công", result.isSuccess)

        val user = result.getOrNull()
        assertEquals("new_sb_user_id", user?.id)
        assertEquals("newuser@epubpro.app", user?.email)
        assertEquals("new_sb_access_token", user?.token)
        assertEquals("new_sb_refresh_token", user?.refreshToken)
        assertEquals("Nguyễn Văn B", user?.displayName)
    }

    @Test
    fun testSendPasswordResetEmailWithSupabase(): Unit = runBlocking {
        whenever(mockBackendAuthApiService.getAuthConfig()).thenReturn(supabaseConfig)

        whenever(
            mockSupabaseAuthApiService.recoverPassword(any(), any(), any())
        ).thenReturn(Response.success("".toResponseBody(null)))

        val result = authRepository.sendPasswordResetEmail("reset@epubpro.app")
        assertTrue("Gửi email khôi phục thành công", result.isSuccess)

        verify(mockSupabaseAuthApiService).recoverPassword(
            url = eq("https://mockproject.supabase.co/auth/v1/recover"),
            apiKey = eq("mock_publishable_key_123"),
            body = eq(SupabaseRecoverRequestDto(email = "reset@epubpro.app"))
        )
        Unit
    }
}
