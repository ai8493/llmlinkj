package com.ai8493.llmproxy.client;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Tool;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.ai8493.llmproxy.adapter.anthropic.*;
import com.ai8493.llmproxy.config.BackendConfig;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class BackendClientFactory {

    public static Client createClient(BackendConfig config) {
        Client.Builder builder = Client.builder()
                                       .apiKey(config.apiKey());

        if (config.baseUrl() != null && !config.baseUrl().isEmpty()) {
            builder.httpOptions(HttpOptions.builder()
                                           .baseUrl(config.baseUrl())
                                           .build());
        }

        return builder.build();
    }

    public static OpenAIClient createOpenAiClient(BackendConfig config) {
        Logger log = LoggerFactory.getLogger("okhttp3.OpenAiBackend." + config.protocol());

        OkHttpClient rawClient = new OkHttpClient.Builder()
                .addInterceptor(new LoggingInterceptor(log))
                .connectTimeout(config.connectTimeout())
                .readTimeout(config.readTimeout())
                .writeTimeout(config.writeTimeout())
                .build();

        com.openai.client.okhttp.OkHttpClient sdkClient =
                new com.openai.client.okhttp.OkHttpClient(rawClient);

        ClientOptions options = ClientOptions.builder()
                                             .baseUrl(config.baseUrl())
                                             .apiKey(config.apiKey())
                                             .httpClient(sdkClient)
                                             .timeout(config.readTimeout())
                                             .maxRetries(0)
                                             .build();

        return new OpenAIClientImpl(options);
    }

    /**
     * 创建 Anthropic 后端 HTTP 客户端。
     * <p>
     * 注意：Anthropic SDK 的 {@link AnthropicOkHttpClient.Builder} 内部自行管理 OkHttpClient，
     * 不提供注入自定义 OkHttpClient/Interceptor 的入口。SDK 自身通过 {@code LoggingHttpClient}
     * 实现了内置的 HTTP 日志（由 {@code com.anthropic.core.LogLevel} 控制），
     * 因此此处不额外添加 {@link LoggingInterceptor}。
     */
    public static AnthropicClient createAnthropicClient(BackendConfig config) {
        // ANTHROPIC_LOG 环境变量控制 HTTP 报文日志级别：off/info/error/debug，默认 OFF
        var logLevel = com.anthropic.core.LogLevel.Companion.fromEnv();

        var realBackendBuilder = AnthropicBackend.builder().apiKey(config.apiKey());
        if (config.baseUrl() != null && !config.baseUrl().isEmpty()) {
            realBackendBuilder.baseUrl(config.baseUrl());
        }
        var realBackend = realBackendBuilder.build();

        var reorderMapper = ObjectMappers.jsonMapper()
                                         .rebuild()
                                         .addMixIn(MessageCreateParams.Body.class, AnthropicBodyOrderMixIn.class)
                                         .addMixIn(Tool.class, ToolOrderMixIn.class)
                                         .addMixIn(Tool.InputSchema.class, InputSchemaOrderMixIn.class)
                                         .addMixIn(MessageParam.class, MessageParamOrderMixIn.class)
                                         .build();

        var builder = AnthropicOkHttpClient.builder()
                                           .backend(new LoggingBackend(realBackend))
                                           .jsonMapper(reorderMapper)
                                           .logLevel(logLevel)
                                           .maxRetries(0);

        var readTimeout = config.readTimeout();
        if (readTimeout != null) {
            builder.timeout(readTimeout);
        }

        return builder.build();
    }

    /**
     * 自定义 OkHttp 拦截器：打印请求/响应（脱敏 Authorization），仅 DEBUG 时生效
     */
    private static class LoggingInterceptor implements Interceptor {
        private static final String REDACTED_AUTH = "Bearer ****";

        private final Logger log;

        LoggingInterceptor(Logger log) {
            this.log = log;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            if (!log.isDebugEnabled()) {
                return chain.proceed(chain.request());
            }

            var req = chain.request();
            var reqLine = new StringBuilder();
            reqLine.append("--> ").append(req.method()).append(' ').append(req.url()).append('\n');
            req.headers().forEach(h -> {
                reqLine.append(h.component1()).append(": ");
                reqLine.append("Authorization".equalsIgnoreCase(h.component1())
                        ? REDACTED_AUTH : h.component2());
                reqLine.append('\n');
            });
            if (req.body() != null && !req.body().isOneShot()) {
                var buffer = new Buffer();
                req.body().writeTo(buffer);
                reqLine.append(new String(buffer.readByteArray(), StandardCharsets.UTF_8));
            }
            log.debug("{}", reqLine);

            long startNs = System.nanoTime();
            var resp = chain.proceed(req);
            long tookMs = (System.nanoTime() - startNs) / 1_000_000;

            if (log.isDebugEnabled()) {
                var respLine = new StringBuilder();
                respLine.append("<-- ").append(resp.code()).append(' ').append(resp.message())
                        .append(" (").append(tookMs).append("ms)\n");
                resp.headers().forEach(h -> respLine.append(h.component1())
                                                    .append(": ").append(h.component2()).append('\n'));
                if (resp.body() != null && !isStreamingResponse(resp)) {
                    var bodyStr = resp.peekBody(Long.MAX_VALUE).string();
                    respLine.append(bodyStr);
                }
                log.debug("{}", respLine);
            }

            return resp;
        }

        private static boolean isStreamingResponse(Response resp) {
            var contentType = resp.header("Content-Type");
            if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
                return true;
            }
            var transferEncoding = resp.header("Transfer-Encoding");
            return transferEncoding != null && transferEncoding.toLowerCase().contains("chunked");
        }
    }

}
