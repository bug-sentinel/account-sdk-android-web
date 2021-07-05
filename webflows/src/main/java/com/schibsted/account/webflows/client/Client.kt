package com.schibsted.account.webflows.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.schibsted.account.webflows.activities.AuthorizationManagementActivity
import com.schibsted.account.webflows.api.HttpError
import com.schibsted.account.webflows.api.SchibstedAccountApi
import com.schibsted.account.webflows.persistence.EncryptedSharedPrefsStorage
import com.schibsted.account.webflows.persistence.SessionStorage
import com.schibsted.account.webflows.persistence.StateStorage
import com.schibsted.account.webflows.persistence.compat.MigratingSessionStorage
import com.schibsted.account.webflows.token.TokenError
import com.schibsted.account.webflows.token.TokenHandler
import com.schibsted.account.webflows.token.UserTokens
import com.schibsted.account.webflows.user.StoredUserSession
import com.schibsted.account.webflows.user.User
import com.schibsted.account.webflows.util.Either
import com.schibsted.account.webflows.util.Either.Left
import com.schibsted.account.webflows.util.Either.Right
import com.schibsted.account.webflows.util.Logging
import com.schibsted.account.webflows.util.Util
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.*

data class OAuthError(val error: String, val errorDescription: String?) {
    companion object {
        fun fromJson(json: String): OAuthError {
            val parsed = JSONObject(json)
            return OAuthError(
                parsed.getString("error"),
                parsed.optString("error_description")
            )
        }
    }
}

private fun TokenError.toOauthError(): OAuthError? {
    if (this is TokenError.TokenRequestError && cause is HttpError.ErrorResponse && cause.body != null) {
        return OAuthError.fromJson(cause.body)
    }

    return null
}

sealed class LoginError {
    /** Failed to read stored [AuthState]. */
    object AuthStateReadError : LoginError()

    /** Auth response not matching stored [AuthState]. */
    object UnsolicitedResponse : LoginError()

    /**
     * Authentication error response.
     *
     * @see <a href="https://openid.net/specs/openid-connect-core-1_0.html#AuthError" target="_top">Authentication Error Response</a>
     */

    data class AuthenticationErrorResponse(val error: OAuthError) : LoginError()

    /**
     * Token error response.
     *
     * @see <a href="https://openid.net/specs/openid-connect-core-1_0.html#TokenErrorResponse" target="_top">Token Error Response</a>
     */
    data class TokenErrorResponse(val error: OAuthError) : LoginError()

    /** Something went wrong. */
    data class UnexpectedError(val message: String) : LoginError()
}

sealed class RefreshTokenError {
    object NoRefreshToken : RefreshTokenError()
    object ConcurrentRefreshFailure : RefreshTokenError()
    object UserWasLoggedOut : RefreshTokenError()
    data class RefreshRequestFailed(val error: HttpError) : RefreshTokenError()
    data class UnexpectedError(val message: String) : RefreshTokenError()
}

typealias LoginResultHandler = (Either<LoginError, User>) -> Unit

data class SessionStorageConfig(val legacyClientId: String)

/**  Represents a client registered with Schibsted account. */
class Client {
    internal val httpClient: OkHttpClient
    internal val schibstedAccountApi: SchibstedAccountApi
    internal val configuration: ClientConfiguration

    private val tokenHandler: TokenHandler
    private val stateStorage: StateStorage
    private val sessionStorage: SessionStorage
    private val urlBuilder: UrlBuilder

    @JvmOverloads
    constructor (
        context: Context,
        configuration: ClientConfiguration,
        httpClient: OkHttpClient,
        sessionStorageConfig: SessionStorageConfig? = null
    ) {
        this.configuration = configuration
        stateStorage = StateStorage(context.applicationContext)

        if (sessionStorageConfig != null) {
            sessionStorage = MigratingSessionStorage(
                context.applicationContext,
                this,
                sessionStorageConfig.legacyClientId
            )
        } else {
            sessionStorage = EncryptedSharedPrefsStorage(context.applicationContext)
        }

        schibstedAccountApi =
            SchibstedAccountApi(configuration.serverUrl.toString().toHttpUrl(), httpClient)
        tokenHandler = TokenHandler(configuration, schibstedAccountApi)
        this.httpClient = httpClient
        this.urlBuilder = UrlBuilder(configuration, stateStorage, AUTH_STATE_KEY)
    }

    internal constructor (
        configuration: ClientConfiguration,
        stateStorage: StateStorage,
        sessionStorage: SessionStorage,
        httpClient: OkHttpClient,
        tokenHandler: TokenHandler,
        schibstedAccountApi: SchibstedAccountApi
    ) {
        this.configuration = configuration
        this.stateStorage = stateStorage
        this.sessionStorage = sessionStorage
        this.tokenHandler = tokenHandler
        this.httpClient = httpClient
        this.schibstedAccountApi = schibstedAccountApi
        this.urlBuilder = UrlBuilder(configuration, stateStorage, AUTH_STATE_KEY)
    }

    /**
     * Start login flow.
     *
     * Requires [AuthorizationManagementActivity.setup] to have been called before this.
     *
     * @param authRequest Authentication request parameters.
     */
    @JvmOverloads
    fun getAuthenticationIntent(context: Context, authRequest: AuthRequest = AuthRequest()): Intent {
        val customTabsIntent = CustomTabsIntent.Builder().build().apply {
            intent.data = generateLoginUrl(authRequest)
        }
        return AuthorizationManagementActivity.createStartIntent(context, customTabsIntent.intent)
    }

    /**
     * Start auth activity manually.
     *
     * @param authRequest Authentication request parameters.
     */
    @JvmOverloads
    fun launchAuth(context: Context, authRequest: AuthRequest = AuthRequest()) {
        CustomTabsIntent.Builder()
            .build()
            .launchUrl(context, generateLoginUrl(authRequest))
    }

    private fun generateLoginUrl(authRequest: AuthRequest): Uri {
        val loginUrl = urlBuilder.loginUrl(authRequest)
        Log.d(Logging.SDK_TAG, "Login url: $loginUrl")
        return Uri.parse(loginUrl)
    }

    /**
     * Call this with the intent received via deep link to complete the login flow.
     *
     * This only needs to be used if manually starting the login flow using [launchAuth].
     * If using [getAuthenticationIntent] this step will be handled for you.
     */
    fun handleAuthenticationResponse(intent: Intent, callback: LoginResultHandler) {
        val authResponse = intent.data?.query ?: return callback(
            Left(LoginError.UnexpectedError("No authentication response"))
        )
        handleAuthenticationResponse(authResponse, callback)
    }

    private fun handleAuthenticationResponse(
        authResponseParameters: String,
        callback: LoginResultHandler
    ) {
        val authResponse = Util.parseQueryParameters(authResponseParameters)
        val stored = stateStorage.getValue(AUTH_STATE_KEY, AuthState::class)
            ?: return callback(Left(LoginError.AuthStateReadError))

        if (stored.state != authResponse["state"]) {
            callback(Left(LoginError.UnsolicitedResponse))
            return
        }
        stateStorage.removeValue(AUTH_STATE_KEY)

        val error = authResponse["error"]
        if (error != null) {
            val oauthError = OAuthError(error, authResponse["error_description"])
            callback(Left(LoginError.AuthenticationErrorResponse(oauthError)))
            return
        }

        val authCode = authResponse["code"]
            ?: return callback(Left(LoginError.UnexpectedError("Missing authorization code in authentication response")))
        makeTokenRequest(authCode, stored) { storedUserSession ->
            storedUserSession
                .foreach { session ->
                    sessionStorage.save(session)
                    callback(Right(User(this, session.userTokens)))
                }
                .left().foreach { err ->
                    Log.d(Logging.SDK_TAG, "Token error response: $err")
                    val oauthError = err.toOauthError()
                    if (oauthError != null) {
                        callback(Left(LoginError.TokenErrorResponse(oauthError)))
                    } else {
                        callback(Left(LoginError.UnexpectedError(err.toString())))
                    }
                }
        }
    }

    internal fun makeTokenRequest(
        authCode: String,
        authState: AuthState,
        callback: (Either<TokenError, StoredUserSession>) -> Unit
    ) {
        tokenHandler.makeTokenRequest(authCode, authState) { result ->
            val session: Either<TokenError, StoredUserSession> = result.map { tokenResponse ->
                Log.d(Logging.SDK_TAG, "Token response: $tokenResponse")
                StoredUserSession(
                    configuration.clientId,
                    tokenResponse.userTokens,
                    Date()
                )
            }
            callback(session)
        }
    }

    /** Resume any previously logged-in user session */
    fun resumeLastLoggedInUser(callback: (User?) -> Unit) {
        sessionStorage.get(configuration.clientId) {
            if (it == null) {
                callback(null)
            } else {
                callback(User(this, it.userTokens))
            }
        }
    }

    internal fun destroySession() {
        sessionStorage.remove(configuration.clientId)
    }

    internal fun refreshTokensForUser(user: User): Either<RefreshTokenError, UserTokens> {
        val refreshToken = user.tokens?.refreshToken ?: return Left(RefreshTokenError.NoRefreshToken)

        val result = tokenHandler.makeTokenRequest(refreshToken, scope = null)
        return when (result) {
            is Right -> {
                val tokens = user.tokens
                if (tokens != null) {
                    val newAccessToken = result.value.access_token
                    val newRefreshToken = result.value.refresh_token ?: refreshToken
                    val userTokens = tokens.copy(
                        accessToken = newAccessToken,
                        refreshToken = newRefreshToken
                    )
                    user.tokens = userTokens
                    val userSession =
                        StoredUserSession(configuration.clientId, userTokens, Date())
                    sessionStorage.save(userSession)
                    Log.i(Logging.SDK_TAG, "Refreshed user tokens: $result")
                    Right(userTokens)
                } else {
                    Log.i(Logging.SDK_TAG, "User has logged-out during token refresh, discarding new tokens.")
                    Left(RefreshTokenError.UnexpectedError("User has logged-out during token refresh"))
                }
            }
            is Left -> {
                Log.e(Logging.SDK_TAG, "Failed to refresh token: $result")
                Left(RefreshTokenError.RefreshRequestFailed(result.value.cause))
            }
        }
    }

    internal fun copy(newClientConfiguration: ClientConfiguration): Client {
        return Client(
            newClientConfiguration,
            stateStorage,
            sessionStorage,
            httpClient,
            tokenHandler,
            schibstedAccountApi
        )
    }

    internal companion object {
        const val AUTH_STATE_KEY = "AuthState"
    }
}
