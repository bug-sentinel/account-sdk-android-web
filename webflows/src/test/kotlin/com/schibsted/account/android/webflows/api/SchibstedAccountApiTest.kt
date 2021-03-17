package com.schibsted.account.android.webflows.api

import Fixtures
import assertError
import assertSuccess
import await
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.schibsted.account.android.webflows.util.Util
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import withServer


class SchibstedAccountApiTest {
    private val tokenRequest = UserTokenRequest(
        "authCode",
        "12345",
        "client1",
        "redirectUri"
    )

    private fun assertAuthCodeTokenRequest(
        expectedTokenRequest: UserTokenRequest,
        actualRequest: RecordedRequest
    ) {
        assertEquals("/oauth/token", actualRequest.path)

        val tokenRequestParams = Util.parseQueryParameters(actualRequest.body.readUtf8())
        val expectTokenRequest = mapOf(
            "grant_type" to "authorization_code",
            "code" to expectedTokenRequest.authCode,
            "code_verifier" to expectedTokenRequest.codeVerifier,
            "client_id" to expectedTokenRequest.clientId,
            "redirect_uri" to expectedTokenRequest.redirectUri
        )
        assertEquals(expectTokenRequest, tokenRequestParams)
    }

    @Test
    fun makeAuthCodeTokenRequestSuccess() {
        val tokenResponse = UserTokenResponse(
            "accessToken1",
            "refreshToken1",
            "idToken1",
            "openid offline_access",
            3600
        )
        val httpResponse = MockResponse().setBody(
            """
            {
                "access_token": "${tokenResponse.access_token}",
                "refresh_token": "${tokenResponse.refresh_token}",
                "id_token": "${tokenResponse.id_token}",
                "scope": "${tokenResponse.scope}",
                "expires_in": "${tokenResponse.expires_in}"
            }
            """.trimIndent()
        )

        withServer(httpResponse) { server ->
            val schaccApi = SchibstedAccountAPI(server.url("/"), OkHttpClient.Builder().build())
            await { done ->
                schaccApi.makeTokenRequest(tokenRequest) { result ->
                    result.assertSuccess { assertEquals(tokenResponse, it) }
                    assertAuthCodeTokenRequest(tokenRequest, server.takeRequest())
                    done()
                }
            }
        }
    }

    @Test
    fun makeRefreshTokenRequestSuccess() {
        val tokenResponse = UserTokenResponse(
            "accessToken1",
            "refreshToken1",
            null,
            null,
            3600
        )
        val httpResponse = MockResponse().setBody(
            """
            {
                "access_token": "${tokenResponse.access_token}",
                "refresh_token": "${tokenResponse.refresh_token}",
                "expires_in": "${tokenResponse.expires_in}"
            }
            """.trimIndent()
        )

        val tokenRequest = RefreshTokenRequest(
            "refreshToken",
            null,
            Fixtures.clientConfig.clientId
        )
        withServer(httpResponse) { server ->
            val schaccApi = SchibstedAccountAPI(server.url("/"), OkHttpClient.Builder().build())
            await { done ->
                val result = schaccApi.makeTokenRequest(tokenRequest)
                result.assertSuccess { assertEquals(tokenResponse, it) }

                val tokenRequestParams =
                    Util.parseQueryParameters(server.takeRequest().body.readUtf8())
                val expectedParams = mapOf(
                    "client_id" to Fixtures.clientConfig.clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to tokenRequest.refreshToken
                )
                assertEquals(expectedParams, tokenRequestParams)
                done()
            }
        }
    }

    @Test
    fun makeTokenRequestConnectionError() {
        val schaccApi =
            SchibstedAccountAPI("http://localhost:1".toHttpUrl(), OkHttpClient.Builder().build())
        await { done ->
            schaccApi.makeTokenRequest(tokenRequest) { result ->
                result.assertError { assertTrue(it is HttpError.UnexpectedError) }
                done()
            }
        }
    }

    @Test
    fun makeTokenRequestErrorResponse() {
        val response = MockResponse().setResponseCode(500).setBody("Something went wrong")
        withServer(response) { server ->
            val schaccApi = SchibstedAccountAPI(server.url("/"), OkHttpClient.Builder().build())
            await { done ->
                schaccApi.makeTokenRequest(tokenRequest) { result ->
                    result.assertError {
                        assertTrue(it is HttpError.ErrorResponse)
                        val error = it as HttpError.ErrorResponse
                        assertEquals(500, error.code)
                        assertEquals("Something went wrong", error.body)
                    }
                    done()
                }
            }
        }
    }

    @Test
    fun jwksSuccess() {
        val jwk = RSAKeyGenerator(2048).generate().toPublicJWK()
        val jwksResponse = MockResponse().setBody(
            """
            {
                "keys": [$jwk]
            }
            """.trimIndent()
        )
        withServer(jwksResponse) { server ->
            val schaccApi = SchibstedAccountAPI(server.url("/"), OkHttpClient.Builder().build())
            await { done ->
                schaccApi.getJwks { result ->
                    result.assertSuccess { assertEquals(listOf(jwk), it.keys) }
                    done()
                }
            }
        }
    }

    @Test
    fun jwksConnectionError() {
        val schaccApi =
            SchibstedAccountAPI("http://localhost:1".toHttpUrl(), OkHttpClient.Builder().build())
        await { done ->
            schaccApi.getJwks { result ->
                result.assertError { assertTrue(it is HttpError.UnexpectedError) }
                done()
            }
        }
    }

    @Test
    fun jwksErrorResponse() {
        val response = MockResponse().setResponseCode(500).setBody("Something went wrong")
        withServer(response) { server ->
            val schaccApi = SchibstedAccountAPI(server.url("/"), OkHttpClient.Builder().build())
            await { done ->
                schaccApi.getJwks { result ->
                    result.assertError {
                        assertTrue(it is HttpError.ErrorResponse)
                        val error = it as HttpError.ErrorResponse
                        assertEquals(500, error.code)
                        assertEquals("Something went wrong", error.body)
                    }
                    done()
                }
            }
        }
    }

    @Test
    fun customUserAgentHeaderIsAddedToRequest() {
        val response = MockResponse().setResponseCode(500).setBody("Something went wrong")
        withServer(response) { server ->
            val schaccApi = SchibstedAccountAPI(server.url("/"), OkHttpClient.Builder().build())
            await { done ->
                schaccApi.getJwks { result ->
                    val userAgentHeader = server.takeRequest().getHeader("User-Agent")
                    assertTrue(userAgentHeader!!.contains("AccountSDKAndroidWeb"))
                    done()
                }
            }
        }
    }
}
