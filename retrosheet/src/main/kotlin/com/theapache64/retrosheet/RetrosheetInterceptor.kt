package com.theapache64.retrosheet

import de.siegmar.fastcsv.reader.CsvReader
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Invocation
import java.net.URLEncoder

/**
 * Created by theapache64 : Jul 21 Tue,2020 @ 02:33
 */
class RetrosheetInterceptor(private val isLoggingEnabled: Boolean = false) : Interceptor {

    companion object {
        private val TAG = RetrosheetInterceptor::class.java.simpleName
        private const val URL_START = "https://docs.google.com/spreadsheets/d"

        private val URL_REGEX by lazy {
            "https://docs\\.google\\.com/spreadsheets/d/(?<docId>.+)/(?<pageName>.+)?".toRegex()
        }

        fun isDouble(field: String): Boolean {
            return try {
                field.toDouble()
                true
            } catch (e: NumberFormatException) {
                false
            }
        }

        fun isInteger(field: String): Boolean {
            return try {
                field.toLong()
                true
            } catch (e: NumberFormatException) {
                false
            }
        }

        fun isBoolean(field: String): Boolean {
            return field.equals("true", ignoreCase = true) || field.equals("false", ignoreCase = true)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        return if (isRetrosheetUrl(request.url())) {
            getResponse(chain, request)
        } else {
            chain.proceed(request)
        }
    }

    private fun getResponse(chain: Interceptor.Chain, request: Request): Response {
        val newRequest = getModifiedRequest(request)
        val response = chain.proceed(newRequest)
        val csvBody = response.body()
            ?: throw IllegalArgumentException("Failed to get CSV data from '${request.url()}'")

        val joRoot = convertCsvToJson(csvBody, newRequest).toString(2)
        println("$TAG : GET <--- $joRoot")
        return response.newBuilder().body(
            ResponseBody.create(
                MediaType.parse("application/json"),
                joRoot
            )
        ).build()
    }

    private fun convertCsvToJson(csvBody: ResponseBody, newRequest: Request): JSONObject {
        return CsvReader().apply {
            setContainsHeader(true)
        }.parse(csvBody.charStream()).use {

            // Parsing CSV
            val sheetName = newRequest.url().queryParameter("sheet")!!
            val joRoot = JSONObject().apply {
                val jaItems = JSONArray().apply {
                    // Loading headers first
                    while (true) {
                        val row = it.nextRow() ?: break
                        val joItem = JSONObject().apply {
                            for (header in it.header) {
                                val field = row.getField(header)
                                when {
                                    isInteger(
                                        field
                                    ) -> {
                                        put(header, field.toLong())
                                    }

                                    isBoolean(
                                        field
                                    ) -> {
                                        put(header, field!!.toBoolean())
                                    }

                                    isDouble(
                                        field
                                    ) -> {
                                        put(header, field.toDouble())
                                    }

                                    else -> {
                                        put(header, field)
                                    }
                                }
                            }
                        }
                        put(joItem)
                    }
                }

                put(sheetName, jaItems)
            }
            joRoot
        }
    }

    /**
     * To modify request with proper URL
     */
    private fun getModifiedRequest(request: Request): Request {

        val url = request.url().toString()
        val matcher =
            URL_REGEX.find(url) ?: throw IllegalArgumentException("URL '$url' doesn't match with expected RegEx")

        // Getting docId from URL
        val docId = matcher.groups[1]?.value
            ?: throw IllegalArgumentException("Couldn't find docId from URL '$url'")

        // Getting page name from URL
        val pageName = matcher.groups[2]?.value
            ?: throw IllegalArgumentException("Couldn't find params from URL '$url'. You must specify the page name")

        // Creating realUrl
        val realUrl = StringBuilder("https://docs.google.com/spreadsheets/d/$docId/gviz/tq?tqx=out:csv&sheet=$pageName")
        request.tag(Invocation::class.java)?.method()?.getAnnotation(Params::class.java)
            ?.let { params ->
                if (params.query.isNotBlank()) {
                    realUrl.append("&tq=${URLEncoder.encode(params.query, "UTF-8")}")
                }

                if (params.range.isNotBlank()) {
                    realUrl.append("&range=${params.range}")
                }

                if (params.headers != -1) {
                    realUrl.append("&headers=${params.headers}")
                }
            }

        val finalUrl = realUrl.toString()
        if (isLoggingEnabled) {
            println("$TAG : GET --> $finalUrl")
        }
        return request.newBuilder()
            .url(finalUrl)
            .build()
    }

    private fun isRetrosheetUrl(httpUrl: HttpUrl): Boolean {
        val url = httpUrl.toString()
        return url.startsWith(URL_START)
    }
}