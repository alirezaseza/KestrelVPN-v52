package com.kestrel.vpn.data.remote
import com.kestrel.vpn.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
sealed interface SubscriptionFetchOutcome {
    data class Success(
        val body: String,
        val etag: String?,
        val lastModified: String?,
        val sourceUrl: String
    ) : SubscriptionFetchOutcome
    data class NotModified(
        val sourceUrl: String
    ) : SubscriptionFetchOutcome
    data class Failure(
        val reason: String
    ) : SubscriptionFetchOutcome
}
@Singleton
class SubscriptionFetcher @Inject constructor(
    @Named("subscription") private val httpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun fetch(
        primaryUrl: String,
        etag: String?,
        lastModified: String?
    ): SubscriptionFetchOutcome = withContext(ioDispatcher) {
        val candidateUrls = SubscriptionConfig.buildCandidateUrls(primaryUrl)
        if (candidateUrls.isEmpty()) {
            return@withContext SubscriptionFetchOutcome.Failure("Subscription URL is empty")
        }
        val errors = mutableListOf<String>()
        for ((index, url) in candidateUrls.withIndex()) {
            if (!url.startsWith("https://", ignoreCase = true)) {
                errors += "Rejected non-HTTPS URL"
                continue
            }
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "KestrelVPN/1.0")
                .header("Accept", "text/plain, */*")
            if (index == 0) {
                if (!etag.isNullOrBlank()) {
                    requestBuilder.header("If-None-Match", etag)
                }
                if (!lastModified.isNullOrBlank()) {
                    requestBuilder.header("If-Modified-Since", lastModified)
                }
            }
            try {
                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    when (response.code) {
                        304 -> {
                            return@withContext SubscriptionFetchOutcome.NotModified(sourceUrl = url)
                        }
                        in 200..299 -> {
                            // A redirect must not downgrade the transfer to plain HTTP.
                            if (!response.request.url.isHttps) {
                                errors += "Redirected to non-HTTPS URL"
                                return@use
                            }
                            val contentType = response.header("Content-Type").orEmpty()
                            if (contentType.startsWith("text/html", ignoreCase = true)) {
                                errors += "Unexpected HTML response (captive portal?) from ${response.request.url.host}"
                                return@use
                            }
                            val contentLength = response.body?.contentLength() ?: -1L
                            if (contentLength > SubscriptionConfig.MAX_SUBSCRIPTION_BYTES) {
                                errors += "Response exceeds size cap ($url)"
                                return@use
                            }
                            val bodyStream = response.body?.byteStream()
                            if (bodyStream == null) {
                                errors += "Empty response body ($url)"
                                return@use
                            }
                            val text = readWithCap(
                                input = bodyStream,
                                maxBytes = SubscriptionConfig.MAX_SUBSCRIPTION_BYTES
                            )
                            if (text == null) {
                                errors += "Stream exceeded size cap ($url)"
                                return@use
                            }
                            return@withContext SubscriptionFetchOutcome.Success(
                                body = text,
                                etag = response.header("ETag"),
                                lastModified = response.header("Last-Modified"),
                                sourceUrl = url
                            )
                        }
                        else -> {
                            errors += "HTTP ${response.code} from ${response.request.url.host}"
                        }
                    }
                }
            } catch (e: IOException) {
                errors += "${e.javaClass.simpleName}: ${e.message ?: "network error"}"
            } catch (e: IllegalArgumentException) {
                errors += "Invalid URL: ${e.message ?: "malformed"}"
            }
        }
        SubscriptionFetchOutcome.Failure(
            reason = errors.joinToString("; ").ifBlank { "All subscription mirrors failed" }
        )
    }
    private fun readWithCap(input: java.io.InputStream, maxBytes: Long): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var totalRead = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            totalRead += read
            if (totalRead > maxBytes) {
                return null
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
