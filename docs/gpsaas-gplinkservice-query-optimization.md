# `GpLinkServiceImpl` 查询调用优化实施指南

## 1. 交付边界

本文只描述以下文件内部的改造：

```text
gpsaas/gpSaaS/gpsaas-module-iot/gpsaas-module-iot-biz/
src/main/java/cn/gaopu/gpsaas/module/iot/rpc/gplink/GpLinkServiceImpl.java
```

实施时必须满足以下边界：

- 不修改 `GpLinkService`、`GpLinkUrlEnum`、任何 DTO、Controller、Service 或调用方。
- `GpLinkServiceImpl` 当前所有 `public` 方法的名称、参数、返回类型和可见性全部不变。
- 不改变 GpLink 的 HTTP 方法、路径、请求体或响应 envelope。响应仍按 `{status,message,result}` 读取。
- `getGpLinkDeviceProperty` 只使用现有
  `/api/v1/device-data/devices/properties/redis` 这一条业务端点，删除客户端对
  `/api/v1/device-data/devices/properties/es` 的第二次回退。Redis 缺项补 TimescaleDB
  是 GpLink 服务端职责，gpSaaS 不再猜测数据源，也不再用“空结果”触发另一条接口。
  一次逻辑调用通常只有一次 `/redis` 请求；仅当第一次收到 HTTP 401 时，允许刷新 token 后对
  同一 `/redis` 请求再尝试一次，因此物理请求数上限为 2，且无论何种结果都不得请求 `/es`。
- 401 以外的 HTTP 错误、业务 envelope 错误、空响应和畸形响应必须抛出异常；不得返回
  `null`、`false` 或空数组来伪装失败。成功 envelope 中合法的空 `result` 数组仍可返回空集合。
- latest/read 的响应超时为 10 秒；history、aggregation、interval 的响应超时为
  35 秒；TCP connect timeout 为 3 秒。
- latest 同时最多 8 个在途调用；history/aggregation/interval 同时最多 2 个在途调用。
  两个 `Semaphore` 都必须使用公平模式。
- 请求结果缓存仅可用于元数据快照：单项最多 64 KiB、总权重最多 8 MiB、固定 TTL
  30 秒。latest、history、aggregation、interval、在线状态、通知和写操作一律不缓存。
- 所有带 `X-Access-Token` 的请求必须与当前 GpLink 配置 URL 同源（scheme、host、有效端口完全
  一致），禁止把 token 发往调用方传入的任意 URL。客户端固定请求 `Accept-Encoding: identity`，
  并拒绝任何非 `identity` 的 `Content-Encoding`。单个响应体硬上限为 32 MiB；这里的字节数是
  JDK 暴露的 identity HTTP entity body 字节数（包含 envelope JSON，不含传输层 chunk framing）。
  超过上限立即终止解码并失败，不能依赖“反序列化后是否进入缓存”来保护堆内存。
- TLS 校验默认开启。仅允许在已隔离的非生产环境、明确接受风险时临时使用现有
  `DISABLE_SSL_VERIFICATION` 兼容开关；生产环境不得使用 trust-all。

这是一份实现说明，不代表 `gpsaas/gpSaaS` 源码已按本文修改或已通过构建。

## 2. 当前问题与目标调用链

当前类同时使用 Hutool `HttpRequest` 和 Spring `RestTemplate`，缓存完整响应字符串；每次取
token 都可能访问 Redis TTL，并在 TTL 较低时滑动续期；`getGpLinkDeviceProperty` 在
`/redis` 为空或异常时再请求 `/es`。这会带来连接池割裂、错误被吞掉、重复查询和不可控的
调用并发。

目标调用链如下：

```text
公开方法
  -> 选择 TrafficClass（LATEST / HEAVY / DEFAULT）
  -> 本地 token 快路径；必要时 JVM 内 single-flight 刷新
  -> 公平 Semaphore（LATEST=8，HEAVY=2，DEFAULT 不占这两个配额）
  -> 对应 RestClient
       shortRestClient: read timeout 10s
       heavyRestClient: read timeout 35s
       二者共享一个 JDK HttpClient: connect timeout 3s
  -> 流式解码 GpEnvelope<T>
  -> 校验 HTTP 状态与 envelope.status
  -> 返回 DTO；只有兼容方法转换一次为 JSONArray
```

`RestClient` 构建完成后是线程安全的。两个 `RestClient` 使用两个
`JdkClientHttpRequestFactory` 设置不同响应超时，但底层共享同一个 JDK
`HttpClient`，因此复用同一连接池。

## 3. 替换前后方法表

以下表中的“直接映射”指从响应流直接反序列化为 `GpEnvelope<T>`，不先创建完整响应
`String`。

| 现有公开方法 | 改造前 | 改造后 | 流量类 | 响应缓存 |
|---|---|---|---|---|
| `createHeaders(String)` | 创建 Hutool 请求头 | 保留签名，返回同样两个 header；内部请求不再依赖它 | DEFAULT | 否 |
| `getHttpResponseJSONArray(String,String)` | Hutool + 完整字符串 + 宽松解析 | `RestClient` 读 `GpEnvelope<JsonNode>`，校验后只把 `result` 转换一次为 `JSONArray` | 按 URL 分类 | 否 |
| `init()` | 初始化 `RestTemplate` 和清理线程 | 初始化一个 JDK `HttpClient`、两个 `RestClient`；不再启动清理线程 | - | - |
| `destroy()` | 关闭缓存清理线程 | 标记 shutdown、清空缓存/本地 token、失败唤醒 refresh waiter；已注册 leader 的至多一次同步 transport 允许排空但不得发布；JDK 17 `HttpClient` 无需关闭 | - | - |
| `getGpLinkConfig()` | 5 分钟本地缓存 | 保留现有行为 | DEFAULT | 独立配置缓存，不计入响应缓存 |
| `getGpLinkToken()` | 登录并返回 token 字符串 | 保留签名，直接执行一次登录；不吞错误 | DEFAULT | 否 |
| `getToken()` | 每次读 Redis，并可能查 TTL/续期 | 先读本地 `TokenState`；仅本地 miss 时 single-flight 读取一次 Redis/TTL 或登录 | DEFAULT | 本地提前 60 秒失效 |
| `createAuthHeader()` | 从 Redis 取 token | 保留签名，使用本地 token 快路径 | DEFAULT | 否 |
| `getGpLinkProductByProductId` | 完整字符串 | 直接映射 DTO | DEFAULT | 可缓存元数据快照 |
| `getGpLinkProductList` | 完整字符串 | 直接映射 `List<DTO>` | DEFAULT | 否（列表可能大且变化） |
| 产品/设备属性元数据查询 | 完整字符串 | 直接映射 `List<DTO>` | DEFAULT | 可缓存元数据快照 |
| 产品/设备属性元数据更新 | 失败可能返回 `false` | 成功返回 `true`，HTTP/业务失败抛异常并清对应元数据缓存 | DEFAULT | 否 |
| `getGpLinkDeviceById` | 完整字符串并手工取 `state.value` | 私有 wire record 直接映射，再组装现有 DTO | DEFAULT | 否（含在线状态） |
| 产品/设备属性配置查询 | 完整字符串 | 直接映射 `List<DTO>` | DEFAULT | 可缓存元数据快照 |
| 设备创建/更新/删除 | 完整字符串 | 直接映射；失败抛异常；成功后失效相关元数据缓存 | DEFAULT | 否 |
| `getGpLinkDeviceProperty` | `/redis`，失败或空时再 `/es` | **只使用 `/redis` 端点**；非 401 一次物理请求，首次 401 时最多再试一次；直接映射私有 wire record | LATEST | 否 |
| 分页历史、无分页历史 | 完整字符串 | 直接映射 page/`List<DTO>` | HEAVY | 否 |
| 聚合查询 | `JSONArray` 手工遍历 | 直接映射 `List<Map<String,List<GpLinkAggRespDTO>>>` 后扁平化 | HEAVY | 否 |
| interval history | `JSONArray` 手工遍历 | 直接映射 `List<List<GpLinkDeviceTrendDto>>` 后组装 Map | HEAVY | 否 |
| 变压器负载率 | 完整字符串 | 直接映射 `List<DTO>` | LATEST | 否 |
| 短信、历史写删、功能调用、MQTT 订阅 | 失败可能变成 `null`/`false` | HTTP 与业务失败抛异常；仅成功 envelope 返回原公开类型；历史写删仍显式使用 DEFAULT | DEFAULT | 否 |
| 产品/设备功能元数据查询 | 完整字符串并用 `contains("result")` 判断 | 直接映射 `List<DTO>`；缺少 `result` 属于畸形响应 | DEFAULT | 否（带查询条件） |

`getHttpResponseJSONArray` 只服务于现有查询调用：路径包含 `/history`、`/agg/` 或
`/interval` 时归入 HEAVY；去掉尾部斜杠后以
`/device-data/devices/properties/redis` 结尾时归入 LATEST；其他为 DEFAULT。历史写删等已知类型的
公开方法不经过这一兼容 helper，而是在各自方法内显式传 `DEFAULT`，因此不会因 URL 含
`/history` 被误归为 35 秒查询。

## 4. 类内实现骨架

### 4.1 import 调整

删除 Hutool HTTP、`RestTemplate`、`SimpleClientHttpRequestFactory`、缓存清理线程和
`HttpURLConnection` 相关 import。保留现有 Fastjson import，因为公共兼容方法及部分请求
仍使用它。新增以下 import；若 IDE 能自动整理，以编译结果为准：

```java
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
```

`TypeReference` 只用于元数据缓存反序列化时才需要；如果所有缓存调用都改用 `JavaType`，
可删除它。不得再保留 `cn.hutool.http.HttpResponse`、`RestTemplateBuilder` 和
`RestTemplate` 两套执行链。

当前类已有并应保留：`@Slf4j` 生成的 `log`、`ServiceException`、`StringRedisTemplate`、
`TOKEN_KEY`、`DISABLE_SSL_VERIFICATION`，以及
`GlobalErrorCodeConstants.*` 中的 `RPC_FAILURE`、`RPC_CONNECT_FAILURE`、
`JSON_PARSE_FAILURE` 静态导入。当前 `GpLinkLoginUserReqDTO` 由 Lombok
`@AllArgsConstructor` 提供 `(String user, String password)` 构造器。若实际分支与这些已核对的定义
不一致，应先在本类适配并以 scoped compile 为准，不得通过修改 DTO 或错误码类来绕开。

### 4.2 常量、字段和私有 wire 类型

下面代码可直接放入 `GpLinkServiceImpl`。`GpEnvelope`、`LatestDeviceWire` 和
`DeviceWire` 都是该类的私有实现细节，不需要新增 DTO 文件。

```java
private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
private static final Duration SHORT_READ_TIMEOUT = Duration.ofSeconds(10);
private static final Duration HEAVY_READ_TIMEOUT = Duration.ofSeconds(35);
// 替换旧值 true；生产默认必须校验证书和 hostname。
private static final boolean DISABLE_SSL_VERIFICATION = false;

private static final long TOKEN_TTL_SECONDS = TimeUnit.MINUTES.toSeconds(25);
private static final long TOKEN_EARLY_EXPIRY_SECONDS = 60;
private static final long CONFIG_CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

private static final long METADATA_CACHE_TTL_MILLIS = Duration.ofSeconds(30).toMillis();
private static final int METADATA_CACHE_MAX_ENTRY_BYTES = 64 * 1024;
private static final long METADATA_CACHE_MAX_TOTAL_BYTES = 8L * 1024 * 1024;
private static final long MAX_RESPONSE_BYTES = 32L * 1024 * 1024;

private static final DefaultRedisScript<Long> DELETE_TOKEN_IF_MATCH =
        new DefaultRedisScript<>(
                "if redis.call('get', KEYS[1]) == ARGV[1] " +
                "then return redis.call('del', KEYS[1]) else return 0 end",
                Long.class);

private static final ParameterizedTypeReference<GpEnvelope<JsonNode>> JSON_ENVELOPE =
        new ParameterizedTypeReference<>() { };
private static final ParameterizedTypeReference<GpEnvelope<List<LatestDeviceWire>>> LATEST_ENVELOPE =
        new ParameterizedTypeReference<>() { };

private final Semaphore latestSemaphore = new Semaphore(8, true);
private final Semaphore heavySemaphore = new Semaphore(2, true);
private final AtomicReference<TokenState> localToken = new AtomicReference<>();
private final AtomicReference<TokenRefreshFlight> tokenRefresh = new AtomicReference<>();
private final AtomicBoolean shuttingDown = new AtomicBoolean();
private final Object lifecycleLock = new Object();

private final Object configCacheLock = new Object();
private volatile GpLinkConfig cachedConfig;
private volatile long configCacheExpiresAtMillis;

private final Object metadataCacheLock = new Object();
private final LinkedHashMap<String, MetadataCacheEntry> metadataCache =
        new LinkedHashMap<>(16, 0.75f, true);
private long metadataCacheBytes;

private HttpClient httpClient;
private RestClient shortRestClient;
private RestClient heavyRestClient;
// 测试可通过 ReflectionTestUtils 换成可控 Clock；不增加公开构造器或方法。
private Clock clock = Clock.systemUTC();

@Resource
private ObjectMapper objectMapper;
// 只用于 GpLink wire contract；init() 中由应用 ObjectMapper copy，不能修改全局 mapper。
private ObjectMapper wireObjectMapper;

private enum TrafficClass {
    DEFAULT, LATEST, HEAVY
}

private record GpEnvelope<T>(Integer status, String message, T result) { }

private record TokenState(String value, long expiresAtMillis) {
    boolean usable(long nowMillis) {
        return value != null && !value.isBlank() && nowMillis < expiresAtMillis;
    }
}

private record TokenRefreshFlight(
        boolean forced,
        CompletableFuture<TokenState> future) { }

private static final class GpLinkHttpStatusException extends RuntimeException {
    private final int status;

    private GpLinkHttpStatusException(int status) {
        super("GpLink HTTP status " + status, null, false, false);
        this.status = status;
    }

    private int status() {
        return status;
    }
}

private static final class ResponseBodyTooLargeException extends IOException {
    private ResponseBodyTooLargeException(long maxBytes) {
        super("GpLink response exceeds " + maxBytes + " bytes");
    }
}

private static final class UnsupportedContentEncodingException extends IOException {
    private UnsupportedContentEncodingException() {
        super("GpLink response Content-Encoding is not identity");
    }
}

private static final class LimitedBodyResponse implements ClientHttpResponse {
    private final ClientHttpResponse delegate;
    private final long maxBytes;
    private InputStream limitedBody;

    private LimitedBodyResponse(ClientHttpResponse delegate, long maxBytes) {
        this.delegate = delegate;
        this.maxBytes = maxBytes;
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
        return delegate.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
        return delegate.getStatusText();
    }

    @Override
    public HttpHeaders getHeaders() {
        return delegate.getHeaders();
    }

    @Override
    public InputStream getBody() throws IOException {
        if (limitedBody == null) {
            limitedBody = new LimitedInputStream(delegate.getBody(), maxBytes);
        }
        return limitedBody;
    }

    @Override
    public void close() {
        delegate.close();
    }
}

private static final class LimitedInputStream extends FilterInputStream {
    private final long maxBytes;
    private long readBytes;

    private LimitedInputStream(InputStream delegate, long maxBytes) {
        super(delegate);
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int read = super.read(bytes, offset, length);
        if (read > 0) {
            count(read);
        }
        return read;
    }

    private void count(int read) throws IOException {
        readBytes += read;
        if (readBytes > maxBytes) {
            throw new ResponseBodyTooLargeException(maxBytes);
        }
    }
}

private record MetadataCacheEntry(String resultJson, int weightBytes, long expiresAtMillis) { }

private record LatestDeviceWire(String id, List<GpLinkDevicePropertyRespDTO> data) { }

private record StateWire(String value) { }

private record DeviceWire(
        String id,
        String name,
        String productId,
        String productName,
        StateWire state) { }

// 必须使用 boxed 类型，才能区分字段缺失与合法的 false/0。
private record MqttCancelWire(
        Boolean success,
        List<String> cancelledIds,
        Integer cancelledCount,
        String message) { }
```

`GpEnvelope` 的 `status` 缺失、`result` 类型不匹配或 JSON 不能反序列化时都属于畸形响应，
不能被当作空结果。这里不能直接使用应用默认 `ObjectMapper`：Jackson 默认可能把
`"status":"200"` 转为 `Integer`，也可能把数字 token 转为 `String`。下一节通过专用 mapper
关闭标量强制转换，确保文档声明与运行时行为一致。

### 4.3 初始化共享 JDK HttpClient 和两个 RestClient

```java
@PostConstruct
public void init() {
    this.wireObjectMapper = objectMapper.copy()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1);

    if (DISABLE_SSL_VERIFICATION) {
        builder.sslContext(buildTrustAllSslContext());
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("");
        builder.sslParameters(sslParameters);
        log.warn("GpLink TLS certificate verification is disabled");
    }

    this.httpClient = builder.build();
    this.shortRestClient = buildRestClient(httpClient, SHORT_READ_TIMEOUT);
    this.heavyRestClient = buildRestClient(httpClient, HEAVY_READ_TIMEOUT);
}

private RestClient buildRestClient(HttpClient sharedClient, Duration readTimeout) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(sharedClient);
    factory.setReadTimeout(readTimeout);
    return RestClient.builder()
            .requestFactory(factory)
            // 不接受压缩，确保 32 MiB 始终是同一种 entity-body 计量口径。
            .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
            .messageConverters(converters -> {
                converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                converters.add(new MappingJackson2HttpMessageConverter(wireObjectMapper));
            })
            .requestInterceptor((request, body, execution) -> {
                ClientHttpResponse response = execution.execute(request, body);
                if (hasNonIdentityContentEncoding(response.getHeaders())) {
                    response.close();
                    throw new UnsupportedContentEncodingException();
                }
                return new LimitedBodyResponse(response, MAX_RESPONSE_BYTES);
            })
            .build();
}

private boolean hasNonIdentityContentEncoding(HttpHeaders headers) {
    List<String> values = headers.get(HttpHeaders.CONTENT_ENCODING);
    if (values == null || values.isEmpty()) {
        return false;
    }
    return values.stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .anyMatch(value -> !"identity".equalsIgnoreCase(value));
}

private SSLContext buildTrustAllSslContext() {
    try {
        TrustManager[] trustManagers = {new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        return context;
    } catch (Exception ex) {
        throw new IllegalStateException("Unable to initialize GpLink SSL context", ex);
    }
}

@PreDestroy
public void destroy() {
    TokenRefreshFlight refreshing;
    synchronized (lifecycleLock) {
        shuttingDown.set(true);
        localToken.set(null);
        refreshing = tokenRefresh.getAndSet(null);
    }
    if (refreshing != null && !refreshing.future().isDone()) {
        refreshing.future().completeExceptionally(shuttingDownFailure());
    }
    clearMetadataCache();
    synchronized (configCacheLock) {
        cachedConfig = null;
        configCacheExpiresAtMillis = 0L;
    }
}

private ServiceException shuttingDownFailure() {
    return new ServiceException(
            RPC_CONNECT_FAILURE.getCode(),
            "GpLink client is shutting down");
}

private long nowMillis() {
    return clock.millis();
}
```

说明：

- Spring Boot 3.4.1 / Spring Framework 6.2 提供 `RestClient` 和
  `JdkClientHttpRequestFactory#setReadTimeout(Duration)`；当前工程 Java 版本为 17。
- JDK 17 的 `HttpClient` 不是 `AutoCloseable`。删除原来的
  `ScheduledExecutorService`，缓存使用读取时过期和写入时逐出，不再需要后台线程。
- 保留 `DISABLE_SSL_VERIFICATION` 名称只是为了兼容当前类内分支，默认值必须由旧实现的
  `true` 改为 `false`。只有隔离的非生产环境可在明确接受风险后临时改为 `true`；正式环境必须
  使用受信证书并校验 hostname。该字段是 `static final` 编译期常量，不能在普通测试中用反射切换；
  若必须覆盖兼容分支，只能用单独编译、绝不发布的隔离测试变体临时改值并在测试后丢弃该产物。
  该风险不能通过记录更多日志来补偿。
- `connectTimeout(3s)` 只约束新建连接；复用连接时没有新的 connect 阶段。10/35 秒由对应
  request factory 约束响应读取。

### 4.4 统一执行器、并发闸门和 envelope 校验

以下 helper 是 Hutool 与 `RestTemplate` 两套路径的共同替代物。

```java
private <T> T executeRequired(
        String url,
        HttpMethod method,
        Object body,
        TrafficClass trafficClass,
        ParameterizedTypeReference<GpEnvelope<T>> responseType) {
    return requireResult(
            executeEnvelope(url, method, body, trafficClass, responseType),
            method,
            url);
}

private <T> GpEnvelope<T> executeEnvelope(
        String url,
        HttpMethod method,
        Object body,
        TrafficClass trafficClass,
        ParameterizedTypeReference<GpEnvelope<T>> responseType) {
    URI targetUri = requireGpLinkOrigin(url);
    String token = getToken();
    for (int attempt = 0; attempt < 2; attempt++) {
        String requestToken = token;
        try {
            return withPermit(trafficClass, () -> {
                log.debug("GpLink request: method={}, route={}, class={}",
                        method, safeRoute(url), trafficClass);
                RestClient.RequestBodySpec request = clientFor(trafficClass)
                        .method(method)
                        .uri(targetUri)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Access-Token", requestToken)
                        .header(HttpHeaders.USER_AGENT, "GpLink-Client/2.0");
                if (body != null) {
                    request.body(body);
                }
                GpEnvelope<T> envelope = request
                        .retrieve()
                        // RestClient 默认只把 4xx/5xx 当错误；这里显式拒绝 3xx 在内的所有非 2xx。
                        .onStatus(status -> !status.is2xxSuccessful(), (httpRequest, response) -> {
                            throw new GpLinkHttpStatusException(response.getStatusCode().value());
                        })
                        .body(responseType);
                return requireSuccessEnvelope(envelope, method, url);
            });
        } catch (GpLinkHttpStatusException ex) {
            if (ex.status() == HttpStatus.UNAUTHORIZED.value() && attempt == 0) {
                token = refreshAfterUnauthorized(requestToken);
                continue;
            }
            if (ex.status() == HttpStatus.UNAUTHORIZED.value()) {
                // 第二次 401 不再重试，但仍清理这个失败 token；compare-delete 不会误删更新值。
                invalidateFailedToken(requestToken);
            }
            throw mapHttpFailure(method, url, ex);
        } catch (ResourceAccessException ex) {
            if (hasCause(ex, ResponseBodyTooLargeException.class)) {
                throw responseTooLarge(method, url);
            }
            if (hasCause(ex, UnsupportedContentEncodingException.class)) {
                throw unsupportedContentEncoding(method, url);
            }
            throw mapTransportFailure(method, url, ex);
        } catch (RestClientException ex) {
            if (hasCause(ex, ResponseBodyTooLargeException.class)) {
                throw responseTooLarge(method, url);
            }
            if (hasCause(ex, UnsupportedContentEncodingException.class)) {
                throw unsupportedContentEncoding(method, url);
            }
            throw malformedResponse(method, url, ex);
        }
    }
    throw new ServiceException(RPC_FAILURE.getCode(), "GpLink authentication retry exhausted");
}

private RestClient clientFor(TrafficClass trafficClass) {
    return trafficClass == TrafficClass.HEAVY ? heavyRestClient : shortRestClient;
}

private <T> T withPermit(TrafficClass trafficClass, Supplier<T> action) {
    Semaphore semaphore = switch (trafficClass) {
        case LATEST -> latestSemaphore;
        case HEAVY -> heavySemaphore;
        case DEFAULT -> null;
    };
    if (semaphore == null) {
        return action.get();
    }

    boolean acquired = false;
    try {
        semaphore.acquire();
        acquired = true;
        return action.get();
    } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new ServiceException(RPC_CONNECT_FAILURE.getCode(),
                "Interrupted while waiting for GpLink concurrency permit");
    } finally {
        if (acquired) {
            semaphore.release();
        }
    }
}

private <T> GpEnvelope<T> requireSuccessEnvelope(
        GpEnvelope<T> envelope,
        HttpMethod method,
        String url) {
    if (envelope == null || envelope.status() == null) {
        throw malformedResponse(method, url, null);
    }
    if (envelope.status() != HttpStatus.OK.value()) {
        // 远端 message 是不可信文本，可能含 token、口令或请求数据；不要记录或向上透传。
        log.warn("GpLink business failure: method={}, route={}, businessStatus={}",
                method, safeRoute(url), envelope.status());
        throw new ServiceException(RPC_FAILURE.getCode(),
                "GpLink business failure: status=" + envelope.status());
    }
    return envelope;
}

private <T> T requireResult(GpEnvelope<T> envelope, HttpMethod method, String url) {
    if (envelope.result() == null) {
        throw malformedResponse(method, url, null);
    }
    return envelope.result();
}

private ServiceException mapHttpFailure(
        HttpMethod method,
        String url,
        GpLinkHttpStatusException ex) {
    int status = ex.status();
    log.warn("GpLink HTTP failure: method={}, route={}, status={}",
            method, safeRoute(url), status);
    return new ServiceException(RPC_FAILURE.getCode(),
            "GpLink HTTP failure: status=" + status);
}

private ServiceException mapTransportFailure(
        HttpMethod method,
        String url,
        ResourceAccessException ex) {
    log.warn("GpLink transport failure: method={}, route={}, cause={}",
            method, safeRoute(url), rootCauseName(ex));
    return new ServiceException(RPC_CONNECT_FAILURE.getCode(),
            "GpLink connection or response timeout");
}

private ServiceException malformedResponse(HttpMethod method, String url, Exception ex) {
    log.warn("GpLink malformed response: method={}, route={}, cause={}",
            method, safeRoute(url), rootCauseName(ex));
    return new ServiceException(JSON_PARSE_FAILURE.getCode(), "Malformed GpLink response");
}

private ServiceException responseTooLarge(HttpMethod method, String url) {
    log.warn("GpLink response rejected: method={}, route={}, reason=body-too-large",
            method, safeRoute(url));
    return new ServiceException(
            RPC_FAILURE.getCode(),
            "GpLink response exceeds configured byte limit");
}

private ServiceException unsupportedContentEncoding(HttpMethod method, String url) {
    log.warn("GpLink response rejected: method={}, route={}, reason=content-encoding",
            method, safeRoute(url));
    return new ServiceException(
            RPC_FAILURE.getCode(),
            "GpLink response Content-Encoding must be identity");
}

private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
    Throwable cursor = throwable;
    while (cursor != null && cursor.getCause() != cursor) {
        if (type.isInstance(cursor)) {
            return true;
        }
        cursor = cursor.getCause();
    }
    return false;
}

private String rootCauseName(Throwable throwable) {
    if (throwable == null) {
        return "unknown";
    }
    Throwable cursor = throwable;
    while (cursor.getCause() != null && cursor.getCause() != cursor) {
        cursor = cursor.getCause();
    }
    return cursor.getClass().getSimpleName();
}
```

这里特意不在 `executeEnvelope` 内捕获 `ServiceException` 后转成 `null`。业务错误、畸形响应和
调用方主动中断都保持失败语义。401 只在第一次请求后刷新，第二次 401 直接抛出。

`Semaphore#acquire()` 的等待时间不计入 HTTP read timeout；等待线程可通过 interrupt 取消，
helper 会恢复线程中断标记并且不会泄漏 permit。如果需要端到端超时，应由现有调用方的任务
超时负责，不能在此类内悄悄缩短 10/35 秒的网络超时。

### 4.5 token 本地快路径、single-flight 和一次重试

下面实现保留 Redis 作为多实例共享 token 的位置，但不再对每个业务请求执行 Redis GET、
TTL 查询或滑动续期：

1. 本地 `TokenState` 可用时零 Redis 调用。
2. 本地 miss 时，single-flight leader 最多读取一次 Redis value 和一次 TTL。
3. Redis token 的 TTL 大于 60 秒才可进入本地缓存；本地过期点为 Redis 实际过期点前 60 秒。
4. Redis 无值、TTL 不足或 Redis 不可用时，leader 登录 GpLink；其他线程等待同一个 future。
5. 收到 401 时只删除与失败 token 相同的本地/Redis 值，避免迟到的 401 删除另一个线程刚刷新的
   token；随后 single-flight 刷新并且原请求最多重试一次。
6. 不调用 `expire`，不做滑动续期。

```java
@Override
public String getToken() {
    if (shuttingDown.get()) {
        throw new ServiceException(RPC_CONNECT_FAILURE.getCode(),
                "GpLink client is shutting down");
    }
    long now = nowMillis();
    TokenState current = localToken.get();
    if (current != null && current.usable(now)) {
        return current.value();
    }
    return refreshTokenSingleFlight(false).value();
}

public String getGpLinkToken() {
    if (shuttingDown.get()) {
        throw shuttingDownFailure();
    }
    return fetchTokenFromGpLink();
}

private String refreshAfterUnauthorized(String failedToken) {
    // 即使本地已经出现新 token，也先 compare-delete Redis 中仍可能残留的失败旧值。
    invalidateFailedToken(failedToken);
    TokenState current = localToken.get();
    long now = nowMillis();
    if (current != null && current.usable(now) && !Objects.equals(current.value(), failedToken)) {
        return current.value();
    }
    return refreshTokenSingleFlight(true).value();
}

private TokenState refreshTokenSingleFlight(boolean forceLogin) {
    for (;;) {
        if (shuttingDown.get()) {
            throw new ServiceException(RPC_CONNECT_FAILURE.getCode(),
                    "GpLink client is shutting down");
        }
        TokenState current = localToken.get();
        if (!forceLogin && current != null && current.usable(nowMillis())) {
            return current;
        }

        TokenRefreshFlight running = tokenRefresh.get();
        if (running != null) {
            TokenState joined = awaitToken(running.future());
            if (!forceLogin || running.forced()) {
                return joined;
            }
            // 401 强制刷新不能复用一个“只读 Redis”的非强制 flight；等待它结束后再竞争强制登录。
            continue;
        }

        CompletableFuture<TokenState> mine = new CompletableFuture<>();
        TokenRefreshFlight myFlight = new TokenRefreshFlight(forceLogin, mine);
        // 与 destroy 使用同一把锁：shutdown 检查与 flight 注册之间不存在 TOCTOU。
        synchronized (lifecycleLock) {
            if (shuttingDown.get()) {
                throw shuttingDownFailure();
            }
            if (!tokenRefresh.compareAndSet(null, myFlight)) {
                continue;
            }
        }

        try {
            TokenState loaded = forceLogin ? loginAndPersistToken() : loadSharedOrLoginToken();
            // 结果发布也与 destroy 原子化：destroy 先发生时绝不会重新写回 localToken。
            synchronized (lifecycleLock) {
                if (shuttingDown.get()) {
                    throw shuttingDownFailure();
                }
                localToken.set(loaded);
                mine.complete(loaded);
            }
            return loaded;
        } catch (Throwable ex) {
            RuntimeException failure = ex instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new ServiceException(
                            RPC_CONNECT_FAILURE.getCode(),
                            "GpLink token refresh failed");
            mine.completeExceptionally(failure);
            if (ex instanceof Error error) {
                throw error;
            }
            throw failure;
        } finally {
            tokenRefresh.compareAndSet(myFlight, null);
        }
    }
}

private TokenState awaitToken(CompletableFuture<TokenState> future) {
    try {
        return future.get();
    } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new ServiceException(RPC_CONNECT_FAILURE.getCode(),
                "Interrupted while waiting for GpLink token refresh");
    } catch (ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof ServiceException serviceException) {
            throw serviceException;
        }
        throw new ServiceException(
                RPC_CONNECT_FAILURE.getCode(),
                "GpLink token refresh failed");
    }
}

private TokenState loadSharedOrLoginToken() {
    try {
        String shared = stringRedisTemplate.opsForValue().get(TOKEN_KEY);
        if (shared != null && !shared.isBlank()) {
            Long ttlSeconds = stringRedisTemplate.getExpire(TOKEN_KEY, TimeUnit.SECONDS);
            if (ttlSeconds != null && ttlSeconds > TOKEN_EARLY_EXPIRY_SECONDS) {
                long localTtl = ttlSeconds - TOKEN_EARLY_EXPIRY_SECONDS;
                return new TokenState(shared,
                        nowMillis() + TimeUnit.SECONDS.toMillis(localTtl));
            }
        }
    } catch (RuntimeException ex) {
        log.warn("GpLink shared token unavailable: cause={}", rootCauseName(ex));
    }
    return loginAndPersistToken();
}

private TokenState loginAndPersistToken() {
    String token = fetchTokenFromGpLink();
    long now = nowMillis();
    long localTtlSeconds = TOKEN_TTL_SECONDS - TOKEN_EARLY_EXPIRY_SECONDS;
    synchronized (lifecycleLock) {
        if (shuttingDown.get()) {
            throw new ServiceException(RPC_CONNECT_FAILURE.getCode(),
                    "GpLink client is shutting down");
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    TOKEN_KEY, token, TOKEN_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException ex) {
            log.warn("GpLink token obtained but Redis persistence failed: cause={}", rootCauseName(ex));
        }
    }
    return new TokenState(token, now + TimeUnit.SECONDS.toMillis(localTtlSeconds));
}

private String fetchTokenFromGpLink() {
    GpLinkConfig config = getGpLinkConfig();
    GpLinkLoginUserReqDTO login = new GpLinkLoginUserReqDTO(
            config.getUser(), config.getPassword());
    String url = GpLinkUrlEnum.GET_TOKEN.getCompleteUrl(config.getUrl());
    URI targetUri = requireGpLinkOrigin(url);
    try {
        GpEnvelope<String> envelope = shortRestClient.post()
                .uri(targetUri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(login)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (httpRequest, response) -> {
                    throw new GpLinkHttpStatusException(response.getStatusCode().value());
                })
                .body(new ParameterizedTypeReference<>() { });
        String token = requireResult(
                requireSuccessEnvelope(envelope, HttpMethod.POST, url),
                HttpMethod.POST,
                url);
        if (token.isBlank()) {
            throw malformedResponse(HttpMethod.POST, url, null);
        }
        return token;
    } catch (GpLinkHttpStatusException ex) {
        throw mapHttpFailure(HttpMethod.POST, url, ex);
    } catch (ResourceAccessException ex) {
        if (hasCause(ex, ResponseBodyTooLargeException.class)) {
            throw responseTooLarge(HttpMethod.POST, url);
        }
        if (hasCause(ex, UnsupportedContentEncodingException.class)) {
            throw unsupportedContentEncoding(HttpMethod.POST, url);
        }
        throw mapTransportFailure(HttpMethod.POST, url, ex);
    } catch (RestClientException ex) {
        if (hasCause(ex, ResponseBodyTooLargeException.class)) {
            throw responseTooLarge(HttpMethod.POST, url);
        }
        if (hasCause(ex, UnsupportedContentEncodingException.class)) {
            throw unsupportedContentEncoding(HttpMethod.POST, url);
        }
        throw malformedResponse(HttpMethod.POST, url, ex);
    }
}

private void invalidateFailedToken(String failedToken) {
    localToken.updateAndGet(current ->
            current != null && Objects.equals(current.value(), failedToken) ? null : current);
    if (failedToken == null || failedToken.isBlank()) {
        return;
    }
    try {
        stringRedisTemplate.execute(
                DELETE_TOKEN_IF_MATCH,
                Collections.singletonList(TOKEN_KEY),
                failedToken);
    } catch (RuntimeException ex) {
        log.warn("GpLink stale token cleanup failed: cause={}", rootCauseName(ex));
    }
}
```

注意：`fetchTokenFromGpLink` 不允许走带认证重试的 `executeEnvelope`，否则 token 端点 401 会递归
刷新。登录请求使用 10 秒响应超时，不占 latest/heavy permit；登录 `result` 为 `null`、空串或纯空白
都按畸形响应失败。`awaitToken` 必须使用可中断的 `get()`，不能用 `join()` 吞掉等待线程的中断。
销毁开始后不再接受新 refresh flight。这里把 `tokenRefresh` 在 `lifecycleLock` 内成功注册定义为
“flight 已开始”：若 `destroy()` 先取得锁，则后续调用不得发起 token HTTP；若 leader 已先完成
注册，JDK 17 的同步 `RestClient` 调用无法由完成 `CompletableFuture` 可靠中止，因此该**唯一已注册
leader** 仍可能开始或完成至多一次既有 token transport。`destroy()` 取得 `lifecycleLock` 后必须以
`RPC_CONNECT_FAILURE` 唤醒 waiter，并阻止该 leader 写 Redis、发布 `localToken` 或注册第二个
flight。由于 `loginAndPersistToken` 为保证发布线性化而在该锁内执行 Redis SET，若 SET 本身阻塞，
`destroy()` 会先等待该临界区退出，不能声称从方法调用瞬间就能唤醒 waiter。transport 成功返回后，
leader 必须在发布检查处以 shutdown failure 结束；若 transport 自身先以 HTTP/超时错误失败，则可
保留该原始失败。两种情况都不得发布 token。不要声称 `destroy()` 能同步取消已经注册的
阻塞 HTTP 调用。生命周期的线性化点是 `destroy()` 在 `lifecycleLock` 内把 `shuttingDown` 设为
`true`；已在该点之前持同一锁完成的 Redis/local 发布属于 destroy 之前，线性化点之后不得再发布。

### 4.6 元数据专用加权缓存

不要缓存完整 HTTP envelope，也不要缓存 latest/history/aggregation。下面 helper 只保存
`result` 的 JSON 字符串快照：字符串不可变、不会向调用方暴露；命中时重新创建 DTO，因此调用方
修改返回 DTO 不会污染缓存。权重按 key 和 UTF-8 payload 的实际字节数计算，而不是 entry 数量。

```java
private <T> T getOrLoadMetadata(
        String key,
        JavaType javaType,
        Supplier<T> loader) {
    if (shuttingDown.get()) {
        throw shuttingDownFailure();
    }
    Optional<String> cached = getMetadataJson(key);
    if (cached.isPresent()) {
        try {
            T decoded = wireObjectMapper.readValue(cached.get(), javaType);
            if (shuttingDown.get()) {
                throw shuttingDownFailure();
            }
            return decoded;
        } catch (JsonProcessingException ex) {
            removeMetadataCache(key);
            log.warn("GpLink metadata cache decode failed");
        }
    }

    T loaded = loader.get();
    if (loaded == null) {
        throw new ServiceException(JSON_PARSE_FAILURE.getCode(),
                "GpLink metadata result is null");
    }
    putMetadataCache(key, loaded);
    return loaded;
}

private Optional<String> getMetadataJson(String key) {
    long now = nowMillis();
    synchronized (metadataCacheLock) {
        MetadataCacheEntry entry = metadataCache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (now >= entry.expiresAtMillis()) {
            removeMetadataCacheLocked(key);
            return Optional.empty();
        }
        return Optional.of(entry.resultJson());
    }
}

private void putMetadataCache(String key, Object result) {
    // loader 可能与 destroy 竞态；先避免无意义编码，锁内仍须再次检查。
    if (shuttingDown.get()) {
        return;
    }
    final String json;
    try {
        json = wireObjectMapper.writeValueAsString(result);
    } catch (JsonProcessingException ex) {
        log.warn("GpLink metadata cache encode skipped");
        return;
    }

    int weight = key.getBytes(StandardCharsets.UTF_8).length
            + json.getBytes(StandardCharsets.UTF_8).length;
    if (weight > METADATA_CACHE_MAX_ENTRY_BYTES) {
        log.debug("GpLink metadata cache entry skipped: bytes={}", weight);
        return;
    }

    MetadataCacheEntry newEntry = new MetadataCacheEntry(
            json,
            weight,
            nowMillis() + METADATA_CACHE_TTL_MILLIS);
    synchronized (metadataCacheLock) {
        if (shuttingDown.get()) {
            return;
        }
        removeMetadataCacheLocked(key);
        metadataCache.put(key, newEntry);
        metadataCacheBytes += weight;
        while (metadataCacheBytes > METADATA_CACHE_MAX_TOTAL_BYTES
                && !metadataCache.isEmpty()) {
            String eldest = metadataCache.keySet().iterator().next();
            removeMetadataCacheLocked(eldest);
        }
    }
}

private void removeMetadataCache(String key) {
    synchronized (metadataCacheLock) {
        removeMetadataCacheLocked(key);
    }
}

private void removeMetadataCacheLocked(String key) {
    MetadataCacheEntry removed = metadataCache.remove(key);
    if (removed != null) {
        metadataCacheBytes -= removed.weightBytes();
    }
}

private void clearMetadataCache() {
    synchronized (metadataCacheLock) {
        metadataCache.clear();
        metadataCacheBytes = 0;
    }
}
```

允许使用缓存的方法与 key、失效关系固定如下。key 在内存中使用原始 ID，不得先做日志脱敏；为
避免分隔符碰撞，每个 ID 采用 `UTF-8字节数:原值` 的长度前缀编码。key 不写日志，因此无需也不得
用会发生碰撞的“脱敏值”替换真实 ID。

| 查询方法 | key | 成功写操作后的失效 |
|---|---|---|
| `getGpLinkProductByProductId(productId)` | `product|{len}:{productId}` | 本类没有产品写方法；固定 30 秒到期 |
| `getGpLinkMetadataPropertyList(productId)` | `product-property|{len}:{productId}` | `updateGpLinkMetadataPropertyList` 同时删除本 key 与同产品 `product-config` |
| `getGpLinkProductPropertyConfigById(productId)` | `product-config|{len}:{productId}` | `updateGpLinkMetadataPropertyList` 同时删除本 key 与同产品 `product-property` |
| `getGpLinkDeviceMetadataPropertyList(deviceId)` | `device-property|{len}:{deviceId}` | 设备创建、设备元数据更新、设备更新或删除时与 `device-config` 一起删除 |
| `getGpLinkDevicePropertyConfigById(deviceId)` | `device-config|{len}:{deviceId}` | 设备创建、设备元数据更新、设备更新或删除时与 `device-property` 一起删除 |

其余方法即使响应很小也不得调用 `getOrLoadMetadata`。尤其 `getGpLinkDeviceById` 含
`state.value`，不是不可变元数据；`getDeviceFunctionList` 和 `getProductFunctionList` 接收查询条件，
本次不缓存，避免遗漏条件或函数变更造成错误复用。

该缓存 helper **不提供 miss single-flight**：同一 key 的并发 miss 可以各自调用远端，随后最后一次
成功写入覆盖同 key snapshot；本类也不为 DEFAULT 元数据查询新增 semaphore。其并发度继续由现有
调用方执行器和共享 HTTP client 行为约束。本节只保证顺序请求的命中、固定 TTL、字节上界、快照
隔离和写后失效，不得把“并发 miss 只发一次请求”写入验收结论。`destroy()` 与 loader 完成竞态时，
`putMetadataCache` 的锁外、锁内两次 shutdown 检查保证清空后不会重新填入缓存。

```java
private String metadataKey(String namespace, String id) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(id, "id");
    int byteLength = id.getBytes(StandardCharsets.UTF_8).length;
    return namespace + "|" + byteLength + ":" + id;
}
```

列表类型的调用示例：

```java
JavaType type = objectMapper.getTypeFactory().constructCollectionType(
        List.class, GpLinkMetadataPropertyRespDTO.class);
return getOrLoadMetadata(metadataKey("product-property", productId), type, () ->
        executeRequired(
                url,
                HttpMethod.GET,
                null,
                TrafficClass.DEFAULT,
                new ParameterizedTypeReference<
                        GpEnvelope<List<GpLinkMetadataPropertyRespDTO>>>() { }));
```

更新成功后应按表格删除相关 key；不要等待 30 秒 TTL。设备创建/更新/删除继续调用改造后的
`invalidateDeviceCache`，但该方法只操作上述两条设备元数据 key，不再操作旧的完整响应字符串缓存。

### 4.7 独立保留配置的 5 分钟缓存

`getGpLinkConfig()` 的既有 5 分钟行为必须保留，但它不属于 HTTP 响应缓存，也不计入 metadata 的
8 MiB。删除旧 `requestCache` 和 `CacheItem` 时不要删除本节的独立字段。配置 miss 在类内串行加载，
命中不续期；`destroy()` 按 4.3 的代码清空它，销毁后不得重新加载或发布配置。

```java
@Override
public GpLinkConfig getGpLinkConfig() {
    if (shuttingDown.get()) {
        throw shuttingDownFailure();
    }
    long now = nowMillis();
    GpLinkConfig current = cachedConfig;
    if (current != null && now < configCacheExpiresAtMillis) {
        return current;
    }
    synchronized (configCacheLock) {
        if (shuttingDown.get()) {
            throw shuttingDownFailure();
        }
        now = nowMillis();
        current = cachedConfig;
        if (current != null && now < configCacheExpiresAtMillis) {
            return current;
        }
        final GpLinkConfig loaded;
        try {
            loaded = JSONObject.parseObject(
                    configurationService
                            .getConfigurationByCode("GpLinkConfig")
                            .getConfiguration(),
                    GpLinkConfig.class);
        } catch (RuntimeException ex) {
            throw new ServiceException(
                    JSON_PARSE_FAILURE.getCode(),
                    "GpLink configuration is unavailable");
        }
        if (loaded == null) {
            throw new ServiceException(
                    JSON_PARSE_FAILURE.getCode(),
                    "GpLink configuration is malformed");
        }
        if (shuttingDown.get()) {
            throw shuttingDownFailure();
        }
        cachedConfig = loaded;
        configCacheExpiresAtMillis = nowMillis() + CONFIG_CACHE_TTL_MILLIS;
        return loaded;
    }
}
```

## 5. 关键公开方法替换片段

### 5.1 latest：只使用 `/redis` 端点

这是本次最重要的行为改动。删除 `try/catch`、空列表判断和 `/es` URL 构造：

```java
@Override
public List<GpLinkDeviceDataRespDTO> getGpLinkDeviceProperty(
        GpLinkDeviceDataReqDTO reqDTO) {
    String url = GpLinkUrlEnum.GET_DEVICE_PROPERTY_REDIS
            .getCompleteUrl(getGpLinkConfig().getUrl());

    List<LatestDeviceWire> wireRows = executeRequired(
            url,
            HttpMethod.POST,
            reqDTO,
            TrafficClass.LATEST,
            LATEST_ENVELOPE);

    List<GpLinkDeviceDataRespDTO> result = new ArrayList<>(wireRows.size());
    for (LatestDeviceWire row : wireRows) {
        if (row == null
                || row.id() == null
                || row.data() == null
                || row.data().stream().anyMatch(Objects::isNull)) {
            throw malformedResponse(HttpMethod.POST, url, null);
        }
        GpLinkDeviceDataRespDTO dto = new GpLinkDeviceDataRespDTO();
        dto.setDeviceId(row.id());
        // 保持旧调用方可修改返回集合的兼容性；缓存不可变不等于公开返回值必须不可变。
        dto.setData(new ArrayList<>(row.data()));
        result.add(dto);
    }
    return result;
}
```

删除原 `parseDevicePropertyResponse(String)`。HTTP 500、连接失败、业务 `status != 200`、空 body
或无法解析时都直接失败；只有 GpLink 明确返回 `status=200,result=[]` 才返回空列表。

### 5.2 兼容 `JSONArray` 的公共方法

该方法的公开签名必须保留，因为 `DeviceDataServiceImpl` 和 charge 模块现有调用方依赖它：

```java
@Override
public JSONArray getHttpResponseJSONArray(String url, String body) {
    TrafficClass trafficClass = classifyUrl(url);
    JsonNode result = executeRequired(
            url,
            HttpMethod.POST,
            body,
            trafficClass,
            JSON_ENVELOPE);
    if (!result.isArray()) {
        throw malformedResponse(HttpMethod.POST, url, null);
    }
    // 只转换 result 一次；不创建、记录或缓存完整响应 String。
    return JSONArray.parseArray(result.toString());
}

private TrafficClass classifyUrl(String url) {
    String path = normalizedPath(url);
    while (path.length() > 1 && path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
    }
    List<String> segments = Arrays.asList(path.split("/"));
    if (segments.contains("history")
            || segments.contains("agg")
            || segments.contains("interval")) {
        return TrafficClass.HEAVY;
    }
    if (path.endsWith("/device-data/devices/properties/redis")) {
        return TrafficClass.LATEST;
    }
    return TrafficClass.DEFAULT;
}
```

不要在该方法中恢复 `try/catch (Exception) { return new JSONArray(); }`。那会重新把 HTTP、认证和
解析故障伪装为“没有数据”。`executeRequired` 随后仍会调用 `requireGpLinkOrigin`，因此即使兼容
方法由调用方传入绝对 URL，也不能把 `X-Access-Token` 发送到其他 origin。

### 5.3 可确定类型的方法直接映射

“严格映射”不等于把所有 DTO 字段都强制为非空。对单个 DTO，只有本类后续明确消费、用于分组或
公开合同声明必需的字段才做非空校验；其余服务端可选字段保持 `null`，不得擅自补默认值。对任何
返回集合，集合对象可以为空但不能为 `null`，集合中的 `null` 元素一律视为畸形响应；嵌套集合逐层
应用同一规则。latest 的 `id/data`、设备详情映射所消费的 `state.value`、interval 分组所消费的
`deviceId` 以及 MQTT cancel 的必需字段属于明确必需项。

```java
private <T> List<T> requireListElements(
        List<T> values,
        HttpMethod method,
        String url) {
    // values 本身已由 executeRequired 保证非 null。
    if (values.stream().anyMatch(Objects::isNull)) {
        throw malformedResponse(method, url, null);
    }
    return values;
}
```

所有直接返回 `List<T>` 的方法都要经过该 helper；嵌套 list/map 使用本节 interval/aggregation 的
结构化校验，不能只检查最外层。

无分页历史：

```java
@Override
public List<GpLinkDevicePropertyRespDTO> getGpLinkDevicePropertyHistory(
        String deviceId,
        String property,
        GpLinkQueryParamDTO queryParam) {
    String url = String.format(
            GpLinkUrlEnum.GET_DEVICE_PROPERTY_HISTORY
                    .getCompleteUrl(getGpLinkConfig().getUrl()),
            deviceId,
            property);
    List<GpLinkDevicePropertyRespDTO> rows = executeRequired(
            url,
            HttpMethod.POST,
            queryParam,
            TrafficClass.HEAVY,
            new ParameterizedTypeReference<
                    GpEnvelope<List<GpLinkDevicePropertyRespDTO>>>() { });
    return requireListElements(rows, HttpMethod.POST, url);
}
```

分页历史把类型替换为
`ParameterizedTypeReference<GpEnvelope<GpLinkDevicePropertyPageRespDTO>>`。产品列表、属性配置、
变压器负载率等规则相同。

聚合响应虽然按设备分组，但类型仍可确定，不需要经过 `String` 或 `JSONArray`：

```java
@Override
public List<GpLinkAggRespDTO> getGpLinkAggDeviceProperty(
        GpLinkAggReqDTO request) {
    String url = GpLinkUrlEnum.GET_AGG_DEVICE_PROPERTY
            .getCompleteUrl(getGpLinkConfig().getUrl());
    List<Map<String, List<GpLinkAggRespDTO>>> groups = executeRequired(
            url,
            HttpMethod.POST,
            request,
            TrafficClass.HEAVY,
            new ParameterizedTypeReference<
                    GpEnvelope<List<Map<String, List<GpLinkAggRespDTO>>>>>() { });

    Set<String> requested = Set.copyOf(request.getDevices());
    List<GpLinkAggRespDTO> result = new ArrayList<>();
    for (Map<String, List<GpLinkAggRespDTO>> group : groups) {
        if (group == null) {
            throw malformedResponse(HttpMethod.POST, url, null);
        }
        for (Map.Entry<String, List<GpLinkAggRespDTO>> entry : group.entrySet()) {
            if (!requested.contains(entry.getKey())) {
                continue;
            }
            if (entry.getValue() == null || entry.getValue().stream().anyMatch(Objects::isNull)) {
                throw malformedResponse(HttpMethod.POST, url, null);
            }
            for (GpLinkAggRespDTO dto : entry.getValue()) {
                dto.setDeviceId(entry.getKey());
                result.add(dto);
            }
        }
    }
    return result;
}
```

`getGpLinkDevicePropertyIntervalHistory` 使用
`GpEnvelope<List<List<GpLinkDeviceTrendDto>>>`，并按下面的唯一规则映射：外层 `null` group
是畸形响应；空 group 合法但因没有可用 key 而跳过；非空 group 内不得有 `null` 元素，每个元素的
非空白 `deviceId` 必须完全相同且必须属于本次请求；同一设备出现第二个 group 时拒绝，不能静默
覆盖。结果使用 `LinkedHashMap` 保持服务端 group 顺序，group 内点顺序原样保留。因空 group 被
跳过，对应请求设备不会在返回 Map 中创建空 key；这是在现有 `List<List<...>>` wire contract 没有
独立设备 key 时唯一不臆造映射的行为。

```java
private Map<String, List<GpLinkDeviceTrendDto>> mapIntervalGroups(
        String url,
        Collection<String> requestedDevices,
        List<List<GpLinkDeviceTrendDto>> groups) {
    Set<String> requested = Set.copyOf(requestedDevices);
    Map<String, List<GpLinkDeviceTrendDto>> result = new LinkedHashMap<>();
    for (List<GpLinkDeviceTrendDto> group : groups) {
        if (group == null) {
            throw malformedResponse(HttpMethod.POST, url, null);
        }
        if (group.isEmpty()) {
            continue;
        }
        String groupDeviceId = null;
        List<GpLinkDeviceTrendDto> snapshot = new ArrayList<>(group.size());
        for (GpLinkDeviceTrendDto row : group) {
            if (row == null || row.getDeviceId() == null || row.getDeviceId().isBlank()) {
                throw malformedResponse(HttpMethod.POST, url, null);
            }
            if (groupDeviceId == null) {
                groupDeviceId = row.getDeviceId();
            } else if (!groupDeviceId.equals(row.getDeviceId())) {
                throw malformedResponse(HttpMethod.POST, url, null);
            }
            snapshot.add(row);
        }
        if (!requested.contains(groupDeviceId)
                || result.putIfAbsent(groupDeviceId, snapshot) != null) {
            throw malformedResponse(HttpMethod.POST, url, null);
        }
    }
    return result;
}
```

### 5.4 只有 envelope 成功才返回布尔值

更新元数据、发送短信、调用设备功能等方法原先可能把异常转换成 `false`。改造后可使用：

```java
GpEnvelope<JsonNode> envelope = executeEnvelope(
        url,
        HttpMethod.POST,
        requestBody,
        TrafficClass.DEFAULT,
        JSON_ENVELOPE);
// executeEnvelope 已验证 status == 200；业务失败已经抛出。
return Boolean.TRUE;
```

如果接口的 `result` 本身就是有意义的 `Boolean`，则应使用
`GpEnvelope<Boolean>` 并通过 `executeRequired` 返回该值；不能用 `message == "success"` 代替
状态校验。

各公开方法的成功值必须按下表处理，不能由实现者自行猜测 `null result` 的语义：

| 方法类别 | envelope 成功后的处理 | `result` 要求 |
|---|---|---|
| latest、history 查询、聚合、interval、产品/设备/元数据/配置/功能查询 | `executeRequired` 直接返回现有 DTO 或列表 | 必须存在且类型严格匹配；空数组仅对集合方法合法 |
| `createGpLinkDevice`、`updateGpLinkDevice`、`deleteGpLinkDevice`、`deleteGpLinkPropertyHistory` | 返回现有 DTO/`Integer`；成功取得结果后再做缓存失效 | 必须存在且类型严格匹配 |
| `updateGpLinkMetadataPropertyList`、`updateGpLinkDeviceMetadataPropertyList`、`sendSms`、`invokeDeviceFunction` | 只要 HTTP 2xx 且 `status == 200` 就返回 `true`；不得再比较远端 `message` | 允许服务端省略 `result`，因为现有公开合同只表达操作成功 |
| `updateGpLinkPropertyHistory` | 显式使用 `TrafficClass.DEFAULT`；成功 envelope 后保持原合同，返回请求 `properties.size()` | 允许省略；不得把请求条数误写成服务端确认条数 |
| `createDeviceMqttSubscribe`、`batchCreateDeviceMqttSubscribe` | 严格读取 `GpEnvelope<String>`，再把字符串放入现有响应 DTO 的 `result` | 必须为非空 String；数字等不得强转 |
| `cancelDeviceMqttSubscribe` | 用私有 `MqttCancelWire` 读取 `success/cancelledIds/cancelledCount/message`，再组装现有 DTO | result 对象、boxed `success`、boxed `cancelledCount` 和 `cancelledIds` 必须存在；`cancelledIds` 可为空数组但不能含 `null`；`message` 可为 `null` 且不写日志 |

`MqttCancelWire` 必须保留 `Boolean/Integer` boxed 字段；若改成 primitive，缺失字段会被误当成
`false/0`。转换只做一次，且必须先校验再组装现有公开 DTO：

```java
MqttCancelWire wire = executeRequired(
        url,
        HttpMethod.POST,
        request,
        TrafficClass.DEFAULT,
        new ParameterizedTypeReference<GpEnvelope<MqttCancelWire>>() { });
if (wire.success() == null
        || wire.cancelledCount() == null
        || wire.cancelledIds() == null
        || wire.cancelledIds().stream().anyMatch(Objects::isNull)) {
    throw malformedResponse(HttpMethod.POST, url, null);
}
GpLinkDeviceMqttSubscribeCancelRespDTO response =
        new GpLinkDeviceMqttSubscribeCancelRespDTO();
response.setSuccess(wire.success());
response.setCancelledIds(new ArrayList<>(wire.cancelledIds()));
response.setCancelledCount(wire.cancelledCount());
response.setMessage(wire.message());
return response;
```

设备创建也必须在成功后调用改造后的 `invalidateDeviceCache(gpLinkDeviceSaveDTO.getId())`，与设备更新、
删除遵守同一失效图。任何 HTTP、解码或业务失败发生时都不得提前失效或写入缓存。

## 6. 错误映射

| 输入情况 | 重试 | 对外行为 | 禁止行为 |
|---|---:|---|---|
| HTTP 2xx，`status=200`，`result` 合法 | 0 | 返回 DTO/集合 | 再请求 `/es` |
| HTTP 2xx，合法空数组 | 0 | 返回空集合/空 `JSONArray` | 把它当错误或触发回退 |
| HTTP 2xx，但业务 `status != 200` | 0 | `ServiceException(RPC_FAILURE)`，只保留数值业务状态，不透传不可信 `message` | `null`、`false`、空数组 |
| HTTP 2xx，但 body 空、JSON 畸形、错误标量类型、缺 `status` 或必需 `result` | 0 | `ServiceException(JSON_PARSE_FAILURE)` | Jackson 自动把字符串/数字强转成目标标量、静默返回空 |
| HTTP 401 | 最多 1 次 | 每次都 compare-delete 对应失败 token；第一次 single-flight 刷新，第二次清理后抛 `RPC_FAILURE` | 无限递归、每线程各自登录、把第二个失败 token 留在本地/Redis |
| HTTP 3xx/400/403/404/429/5xx | 0 | `ServiceException(RPC_FAILURE)`，消息只含 HTTP 状态 | 跟随重定向、转成 `null` 或重试 `/es` |
| connect/read timeout、DNS、连接重置 | 0 | `ServiceException(RPC_CONNECT_FAILURE)` | 把超时记为无数据 |
| 等待 semaphore 时线程被中断 | 0 | 恢复中断位并抛 `RPC_CONNECT_FAILURE` | 吞中断或泄漏 permit |
| identity entity body 超过 32 MiB | 0 | 终止流式解码并抛 `ServiceException(RPC_FAILURE)` | 先完整缓存/反序列化后才检查大小 |
| 响应声明任意非 identity `Content-Encoding` | 0 | 读取/解码 body 前关闭响应并抛 `ServiceException(RPC_FAILURE)` | 依赖自动解压后大小、同时接受压缩与 identity 两种计量口径 |
| 非 http(s)、带 user-info/fragment 或与配置不同源的 URL | 0 | 附加 token 前拒绝并抛 `ServiceException(RPC_FAILURE)` | 向任意 URL 发送 token 或登录口令 |
| destroy 后的新 refresh flight 或 waiter | 0 | `destroy()` 取得生命周期锁后以 `ServiceException(RPC_CONNECT_FAILURE)` 失败；不发布本地 token | `IllegalStateException`、挂起 waiter、销毁后重新写 token |
| destroy 前已注册的唯一 leader | 不新建 flight | 同步 transport 可能继续至多一次；成功 transport 在发布点以 shutdown failure 结束，自身失败则保留原错误；均不写 Redis/本地 token | 声称可同步取消 JDK 阻塞调用、启动第二个 refresh transport |

业务 envelope 错误不是 HTTP 401，不能触发 token 刷新。429 与 5xx 本类也不自动重试；自动重试
会在现有调用方线程池上进一步放大负载。若以后需要退避策略，应另行评审，不能混入本改造。

## 7. 日志脱敏规则

实现统一 URL helper；远端业务 `message`、响应 body、异常 message 和 cache key 一律不写日志，
因为正则无法可靠识别 JSON 引号、`Authorization: Bearer ...`、本地化字段名等所有敏感形式。
同时在附加 token 前做同源校验，日志只写固定 route tag，不输出包含 device/product ID 的动态 path：

```java
private URI requireGpLinkOrigin(String rawUrl) {
    try {
        URI target = URI.create(rawUrl).normalize();
        URI base = URI.create(getGpLinkConfig().getUrl()).normalize();
        String targetScheme = target.getScheme();
        String baseScheme = base.getScheme();
        if (targetScheme == null
                || !(targetScheme.equalsIgnoreCase("http")
                || targetScheme.equalsIgnoreCase("https"))
                || target.getHost() == null
                || target.getUserInfo() != null
                || target.getFragment() != null
                || baseScheme == null
                || base.getHost() == null
                || !targetScheme.equalsIgnoreCase(baseScheme)
                || !target.getHost().equalsIgnoreCase(base.getHost())
                || effectivePort(target) != effectivePort(base)) {
            throw invalidTarget();
        }
        return target;
    } catch (ServiceException ex) {
        throw ex;
    } catch (RuntimeException ex) {
        throw invalidTarget();
    }
}

private int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
        return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
}

private ServiceException invalidTarget() {
    return new ServiceException(
            RPC_FAILURE.getCode(),
            "GpLink target URL is invalid or not same-origin");
}

private String normalizedPath(String rawUrl) {
    try {
        String path = URI.create(rawUrl).normalize().getPath();
        return path == null || path.isBlank() ? "/" : path;
    } catch (RuntimeException ex) {
        return "/";
    }
}

private String safeRoute(String rawUrl) {
    String path = normalizedPath(rawUrl);
    if (path.contains("/authorization/")) return "AUTH";
    if (path.contains("/interval")) return "INTERVAL";
    if (path.contains("/agg/")) return "AGGREGATION";
    if (path.contains("/history")) return "HISTORY";
    if (path.contains("/device-data/")) return "DEVICE_DATA";
    if (path.contains("/metadata")) return "METADATA";
    if (path.contains("/device")) return "DEVICE";
    if (path.contains("/product")) return "PRODUCT";
    return "OTHER";
}
```

日志必须遵守：

- 不记录登录请求体、用户名、密码、`X-Access-Token` 或完整请求 headers。
- 不记录业务请求 body；其中可能包含设备 ID、手机号、通知上下文或功能调用参数。
- 不记录完整响应 body，也不记录远端 `message`。HTTP 错误只记录状态码；业务 envelope 只记录
  数值 `status`。
- URL 只记录固定 route tag，不记录 host、动态 path、query 或 fragment。动态 path 和 query 中可能
  存在 device/product ID 和时间范围。
- 不记录 cache key 或其中的业务 ID；缓存编码/解码日志只记录事件类型和非敏感的字节数。
- 异常只记录根因类名，不在普通日志拼接异常 message；JDK/Spring 异常 message 可能含完整 URL。
- 401 日志只说明发生刷新与是否重试，不记录旧/新 token。

## 8. 精确测试清单与样例

测试样例可在后续实施 `GpLinkServiceImpl` 时放入现有 biz 模块测试目录；测试文件是验证物，不要求
修改任何生产 DTO、枚举或调用方。本次交付严格只新增本文档，没有在 `gpsaas/gpSaaS` 中创建测试
文件或修改源码；若后续仍要求“只允许改一个生产文件”，新增对应测试文件不属于生产源码变更，
但必须单独纳入变更清单，不能声称本次已经运行这些尚未落地的测试。
使用 JUnit 5、Mockito 和本机 stub HTTP server。若项目测试依赖中已有 OkHttp
`MockWebServer` 或 WireMock 可直接使用；否则可用 JDK `HttpServer`，避免为了本类再引入生产
依赖。

### 8.1 latest 单请求测试

给 stub 注册 `/api/v1/device-data/devices/properties/redis`、
`/api/v1/device-data/devices/properties/es` 和 `/api/v1/authorization/token` 三个 handler，分别
计数。Redis mock 初始无 token，token handler 返回：

```json
{"status":200,"message":"success","result":"token-1"}
```

latest handler 返回：

```json
{
  "status": 200,
  "message": "success",
  "result": [
    {"id":"d-2","data":[]},
    {"id":"d-1","data":[{"property":"temp","timestamp":1,"value":22.5}]}
  ]
}
```

断言：

```java
assertThat(service.getGpLinkDeviceProperty(request)).hasSize(2);
assertThat(redisPathCount.get()).isEqualTo(1);
assertThat(esPathCount.get()).isZero();
```

再分别让 latest handler 返回 `result=[]`、HTTP 500、业务 `status=4001`、空 body、非法 JSON、
缺少 `result`：

- 只有 `result=[]` 返回空列表；
- 其余全部抛 `ServiceException`；
- 非 401 case 的 `/redis` 计数都是 1；首次 401 的独立 case 最多为 2；`/es` 始终为 0。

这组测试直接防止后来恢复客户端 `/es` 回退。

### 8.2 HTTP 和 envelope 错误参数化测试

```java
@ParameterizedTest
@ValueSource(ints = {301, 302, 307, 308, 400, 403, 404, 429, 500, 503})
void non401HttpFailureMustPropagate(int status) {
    stubLatest(status, "{\"status\":" + status + ",\"message\":\"secret=x\"}");
    assertThrows(ServiceException.class,
            () -> service.getGpLinkDeviceProperty(latestRequest()));
    assertThat(esPathCount.get()).isZero();
}
```

补充独立 case：HTTP 200 + `status=4001`、HTTP 200 + 非 JSON、HTTP 200 + `{}`、HTTP 200 +
`{"status":200}`，均断言异常。HTTP 200 + `{"status":200,"result":[]}` 才断言空集合。登录端点
对 `result=null`、`result=""` 和纯空白 token 均断言 `JSON_PARSE_FAILURE`；任何 3xx 都不得跟随。
还必须覆盖默认 Jackson 容易误接收的错误类型：`{"status":"200","result":[]}`、token
`{"status":200,"result":123}`、列表接口返回单对象以及整数 DTO 字段返回浮点数，全部断言
`JSON_PARSE_FAILURE`，证明实际请求使用的是 `wireObjectMapper` 而非应用宽松 mapper。

聚合响应另测 `groups=null`、group 为 `null`、请求设备对应的 list 为 `null`、list 内元素为
`null`，全部映射为 `JSON_PARSE_FAILURE`，不能跳过后返回部分结果或泄漏 `NullPointerException`。

DTO/null 语义另做表驱动测试：普通直接映射 DTO 的可选字段缺失时应保持 `null` 并成功返回；任何
列表或嵌套列表中的 `null` 元素必须失败。MQTT cancel 分别缺失 `success`、`cancelledCount`、
`cancelledIds`，或令 `cancelledIds` 含 `null`，均断言 `JSON_PARSE_FAILURE`；合法的
`success=false`、`cancelledCount=0`、`cancelledIds=[]` 必须成功，证明 boxed wire 没有把“缺失”
与合法 `false/0` 混为一谈。

interval 分组覆盖以下唯一映射：空 group 被跳过且 Map 不出现臆造 key；group 为 `null`、元素为
`null`、`deviceId` 为空白、同组混入两个 deviceId、返回未请求 deviceId、同设备重复 group 均失败；
两个合法 group 按服务端顺序进入 `LinkedHashMap`，每组点顺序不变。不要用最后一次 `put` 覆盖重复
group，也不要从请求顺序猜测空 group 属于哪个设备。

### 8.3 401 single-flight 与最多一次重试

并发发起 32 个 latest 请求，但屏障参与方只能设为 8：让占满 latest permit 的首批 8 个
`old-token` 请求到齐后同时返回 401，其余 24 个保持在线程池/permit 外等待。token endpoint 返回
一次 `new-token`，携带新 token 的重试及后续请求返回 200。不要用等待 32 个服务端请求的屏障，
因为公平 semaphore 的 8 个 permit 会使该测试必然死锁。断言：

```java
assertThat(tokenEndpointCount.get()).isEqualTo(1);
assertThat(successCount.get()).isEqualTo(32);
assertThat(maxRequestAttemptsPerLogicalCall.get()).isLessThanOrEqualTo(2);
verify(redisTemplate, never()).expire(
        eq("GPlinkToken"), anyLong(), any(TimeUnit.class));
```

同时验证迟到的旧 token 401 不会删除已经写入的 `new-token`：让一个旧请求延迟到刷新完成后再
返回 401，最终 token endpoint 仍只调用一次。

另一个 case 让重试也返回 401，断言总请求次数为 2、抛 `ServiceException`，没有第三次登录或
第三次业务请求，并断言失败的第二个 token 已从 `localToken` 清除、Redis compare-delete 已用该
token 执行。再构造“本地已有 `new-token`、Redis 仍残留 `old-token`”的状态后触发迟到 401，断言
返回 `new-token` 的同时 Redis 中旧值仍被 compare-delete。

token 生命周期竞态必须使用 latch 精确交错，而不是 `sleep`：

- 在 leader 已注册 flight、token HTTP 已到达并阻塞在测试 stub 时调用 `destroy()`，等待
  `destroy()` 取得 `lifecycleLock` 后断言 waiter 收到 `RPC_CONNECT_FAILURE`，再让 stub 返回成功；
  断言 leader 随后以 shutdown failure 结束，`localToken` 仍为
  `null`，且没有 shutdown 后的 Redis SET。这一 case 明确允许已经注册 leader 的那一次 HTTP 完成。
- 在 leader 取得 `lifecycleLock` 并注册 flight **之前**触发 `destroy()`，断言 token HTTP 计数为 0。
  在 flight 已注册但 transport 尚未越过受控测试 checkpoint 时触发 `destroy()`，允许该唯一 leader
  的 HTTP 计数为 0 或 1，但最终不得大于 1；断言没有第二个 flight、没有第二次 token HTTP，且无
  Redis/本地 token 发布。测试结论不得写成“注册后的同步 HTTP 一定能被取消”。
- 中断一个等待 `CompletableFuture#get()` 的 waiter，断言恢复中断位并得到
  `RPC_CONNECT_FAILURE`；leader 和其他 waiter 不受影响。
- leader 正常完成、发布 token 后再 destroy，断言本地状态被清空；销毁后再次 `getToken()` 立即
  失败，不读取 Redis、不登录。

### 8.4 Redis TTL 与本地提前失效

测试通过 `ReflectionTestUtils.setField(service, "clock", mutableClock)` 注入可控 `Clock`，不使用
真实 `sleep`：

1. Redis 返回 `shared-token` 和 TTL=120 秒。
2. 连续调用 `getToken()` 100 次。
3. 断言 Redis `GET` 和 `getExpire` 各 1 次，`expire` 为 0 次，登录端点为 0 次。
4. 另一个 case 返回 TTL=61 秒，首次进入本地缓存后把可控时钟前进 1001ms；下次调用必须重新进入
   single-flight，而不是继续使用即将在 60 秒内失效的 token。
5. Redis GET/SET 抛异常时，登录成功仍应得到可用本地 token；日志不得含 token 值。

### 8.5 公平并发上限

stub handler 进入时递增 `active`，更新 `maxActive`，等待测试 latch，再在 `finally` 递减。

- 提交 24 个 latest 调用，等待首批进入，断言 `maxLatestActive <= 8`；释放后全部成功。
- 提交 8 个 history 或 interval 调用，断言 `maxHeavyActive <= 2`。
- latest 与 heavy 同时提交，断言两个计数器互不借用配额。
- 中断测试使用可直接 `interrupt()`/`join()` 的专用线程，不用只改变 CompletableFuture 状态的
  `cancel(false)`。在线程捕获异常时记录 `Thread.currentThread().isInterrupted()`，断言得到
  `RPC_CONNECT_FAILURE` 且中断位为真；释放其他请求后 `availablePermits()` 恢复为 8 或 2。
- 公平性不能用单线程 executor 验证。通过反射取得目标 semaphore，以 `directHeld=N` 精确记录并由
  测试线程占满全部 N 个 permit；测试开始前先预热配置和可用的本地 token，确保 worker 到达的下一
  个阻塞点就是目标 semaphore。令 `K=min(3,N)`，再从彼此独立的工作线程按序提交 K 个 waiter。
  每个 worker 在调用公开方法前先触发自己的 `beforeAcquire[i]` latch；测试线程等待该 latch 后，使用
  Awaitility 在有界时间内轮询 `getQueueLength()` 相对前一轮增加 1，才提交下一个 waiter。该估算值
  只作为“线程已经排队”的同步门，不作为公平性结论；最终公平性只由下述 `entered` 顺序证明。
  每次只由测试线程释放 **1 个**直接持有的 permit 并执行
  `directHeld--`，随后等待唯一的预期 waiter 到达 stub 内自己的 `entered[i]` latch；该 waiter 暂时
  不退出，因此不会把 permit 提前转给下一个。K 次进入序号必须严格等于提交序号。最后同时放行这
  K 个 stub waiter，在 `finally` 中释放剩余恰好 `directHeld` 个测试 permit，并等待所有线程完成；
  最终断言 `directHeld == 0` 且 `availablePermits() == N`。这样既不会多释放 permit，也不会把线程池
  FIFO 误判为 semaphore 公平性。

### 8.6 超时分类

- connect timeout 使用受控 black-hole TCP 目标（由网络测试容器/防火墙明确丢弃 SYN，不能是
  已接受 TCP 后不返回 HTTP 的 server），断言接近 3 秒失败并映射为 `RPC_CONNECT_FAILURE`。本机 connection
  refused 只证明“立即拒绝”，不能证明 3 秒 timeout 配置。
- latest stub 接收请求后延迟超过 10 秒，断言失败；history stub 延迟 11 秒但少于 35 秒，
  断言成功。
- 标记为 slow/integration 的 case 让 history 延迟超过 35 秒，断言失败。
- 所有超时 case 均断言没有 `/es` 请求、没有缓存写入、permit 最终归还。

若不希望单测真实等待 35 秒，可在测试中通过 `ReflectionTestUtils` 把
`shortRestClient`/`heavyRestClient` 替换为相同 JDK client 但 50/150ms read timeout 的实例，测试
分类与资源释放；同时保留一个构建配置断言或集成测试确认生产常量确实为 3/10/35 秒。

### 8.7 元数据缓存的字节边界与隔离

必须覆盖：

- 相同元数据请求必须**顺序**执行：等待第一次调用完成并确认缓存 entry 已写入后，再在 30 秒内
  发起第二次，断言远端只调用一次；修改第一次返回 DTO 后，第二次返回值仍为原始内容，证明缓存
  snapshot 未向调用方暴露。不要并发启动这两个调用来验证命中。
- latest、history、aggregation、interval 各连续调用两次，远端都调用两次。
- 单个元数据 `result` 加 key 后为 64 KiB 时允许缓存；为 64 KiB + 1 byte 时两次调用远端两次。
- 写入足够多的 64 KiB metadata，使总权重超过 8 MiB，断言最老 entry 被逐出，
  `metadataCacheBytes <= 8 * 1024 * 1024`。
- 过 30 秒后重新请求；TTL 是固定 TTL，命中不延长过期时间。
- 更新/删除成功立即使相关 key 失效；失败不得错误地写缓存。
- 用 latch 阻塞 metadata loader，确认 flight 已取得远端结果前调用 `destroy()`，随后放行 loader；
  断言 `metadataCacheBytes == 0`、Map 为空，且销毁后的再次查询以 `RPC_CONNECT_FAILURE` 失败。
  本 helper 没有 metadata miss single-flight；并发 miss 允许产生多次远端请求，不纳入“一次请求”验收。
- 配置缓存单独验证：同一 `Clock` 时间内顺序调用 `getGpLinkConfig()` 两次只访问一次
  `configurationService`；推进到 5 分钟到期后再次加载。`destroy()` 后缓存为空且再次调用不访问配置源，
  直接以 `RPC_CONNECT_FAILURE` 失败。该次数不得与 metadata HTTP 缓存统计混在一起。

按 4.6 的表逐项断言完整失效图：产品元数据更新同时删除 `product-property` 与
`product-config`；设备创建、设备元数据更新、设备更新和设备删除都同时删除 `device-property` 与
`device-config`。还要断言函数列表、产品列表、设备详情及所有实时/历史端点从不创建缓存项。

边界 payload 应用 UTF-8 多字节字符构造，断言按 byte 而非 `String.length()` 计算。

### 8.8 `JSONArray` 兼容方法

分别使用 `history/no-paging`、`interval`、普通非重路径 URL：

- history 和 interval 的 handler 同时在途最多 2；普通路径不占 heavy permit。
- 表驱动覆盖当前类全部 URL 形态（含尾部斜杠和 query）：`history`、`agg`、`interval` 必须为
  HEAVY，latest `/redis` 必须为 LATEST，其余为 DEFAULT；分类只解析**完整路径段**，不受 query
  伪造影响。额外断言 `/catalog/history-report`、`/foo/aggregate` 和
  `/plain?next=/history` 均为 DEFAULT，只有含独立 `history`、`agg`、`interval` 段的路径才是 HEAVY。
- `result` 数组正确转换成 Fastjson `JSONArray`，嵌套数组和数字类型保持可读。
- 返回对象不是数组、空 body、错误 status 时抛异常，不返回空 `JSONArray`。
- 使用日志 appender 或测试 logger 捕获输出，构造带引号 JSON secret、
  `Authorization: Bearer multi-word-token`、请求 body 手机号、token 和 query；断言它们以及远端
  `message` 原文均未出现。

### 8.9 同源、TLS 与响应体上界

- 表驱动传入不同 scheme、host、有效端口，及带 user-info/fragment 的 URL，断言在取得 token 或
  发起业务请求前即失败；同 origin 的不同 path/query 正常执行。尤其验证显式 `:443` 与隐式 HTTPS
  443 等价、HTTP 默认 80 等价，但 HTTP 与 HTTPS 不等价。
- 断言所有请求显式发送 `Accept-Encoding: identity`。同源 stub 使用 identity（或不发送
  `Content-Encoding`）返回总 entity body 恰好 32 MiB 与 32 MiB + 1 byte 的**合法 envelope JSON**：
  前者进入正常 JSON 校验流程，后者在流式读取阶段失败；断言未写缓存、permit 已归还，异常和日志
  均不包含响应内容。另让 stub 声明 `gzip`、`br` 及混合编码，断言 body 解码前即失败；不得用
  “压缩前还是解压后”两套 32 MiB 口径。
- 静态检查断言当前构建的 `DISABLE_SSL_VERIFICATION` 为 `false`，并用不受信自签名证书和 hostname
  mismatch 两个 HTTPS stub 断言默认连接失败。它是 `static final` 编译期常量，普通测试不得尝试
  用反射切成 `true`。若确需覆盖兼容分支，只能在单独编译、隔离、不可发布的非生产测试变体中临时
  改值；该变体结果与默认构建结果分开记录，生产测试和交付产物始终保持 `false`。
- `getHttpResponseJSONArray` 传入同源和异源绝对 URL，断言只有同源请求收到
  `X-Access-Token`，异源 server 请求计数始终为 0。

### 8.10 构建与测试命令

在 `gpsaas/gpSaaS` 目录执行：

```powershell
mvn.cmd -pl gpsaas-module-iot/gpsaas-module-iot-biz -am -DskipTests compile
mvn.cmd -pl gpsaas-module-iot/gpsaas-module-iot-biz -am `
  -Dtest=GpLinkServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn.cmd -DskipTests package
```

报告必须分别记录：

- 编译是否通过；
- 单元测试通过、失败或未运行；
- 真实 GpLink/Redis 集成是否执行；
- 全量 package 是否执行；
- 未部署远程服务这一事实。

不要把 `-DskipTests` 的 `BUILD SUCCESS` 写成“测试通过”。

## 9. 实施顺序

1. 增加私有 envelope/wire 类型、超时常量、共享 client、两个 semaphore 和 token 状态字段。
2. 用统一 `executeEnvelope`/`executeRequired` 替换两套 HTTP helper，并先完成错误映射测试。
3. 替换 token 路径，完成 401 single-flight、一次重试和 TTL 测试。
4. 首先替换 `getGpLinkDeviceProperty`，删除 `/es` 回退，并运行单请求测试。
5. 将 history、aggregation、interval 归入 HEAVY；将 latest 归入 LATEST；跑并发上限测试。
6. 逐个把可确定结构的方法改成泛型 envelope/DTO 直接映射。
7. 最后改造 `getHttpResponseJSONArray`，只保留一次 `JsonNode -> JSONArray` 转换。
8. 加入元数据白名单缓存和写后失效；删除旧 `CacheItem`、`requestCache`、清理线程及完整响应
   String 缓存。
9. 运行 scoped compile/test，再运行 standalone 全量 package；集成环境只做显式获准的验证。

每一步都应保持可编译，避免一次性替换 900 余行后才发现类型或异常语义问题。

## 10. 验收复述

独立读者只依据本文，应能得到以下唯一答案：

- latest 调用路径：`getGpLinkDeviceProperty -> /devices/properties/redis`，只使用这一条端点；非 401
  一次物理请求，首次 401 时同端点最多再试一次；无 `/es` 二次回退。
- 超时：共享 JDK client connect 3 秒；普通/latest read 10 秒；history/aggregation/interval
  read 35 秒。
- 401：清理与失败 token 相同的本地/Redis 状态，JVM 内 single-flight 刷新，原请求最多重试
  一次；其他 HTTP/业务错误不重试也不转空。
- 并发：公平 latest semaphore 8，公平 heavy semaphore 2；重试重新获取并释放 permit。
- token：业务请求优先使用本地状态；只在本地 miss 时读取 Redis TTL；本地提前 60 秒过期；
  无滑动续期。
- 缓存：仅元数据 `result` 快照，64 KiB/entry、8 MiB total、固定 TTL 30 秒；latest、history、
  aggregation、interval 不缓存。
- 响应：已知类型直接映射 `GpEnvelope<T>`；只有现有 `JSONArray` 方法转换一次；畸形响应失败。
- 日志：无 token、密码、请求/响应 body、动态 path、query 或远端 message；只保留固定 route tag、
  状态及非敏感根因类名。
- 兼容性：所有现有 public 方法签名不变，无需修改 enum、DTO、Controller 或调用方。

## 11. 已知注意事项

- `GpEnvelope.status == 200` 是当前 GpLink 响应合同。若某个既有成功接口没有 `status` 字段，必须
  先用真实响应证据确认并在本类为该端点做显式适配；不能把“status 缺失即成功”设成全局规则。
- 当前 token 共享 TTL 为 25 分钟，来自旧实现。若服务端实际 token 生命周期不同，应以 token
  响应中的权威过期时间替换该常量；在没有证据前不要臆造更长 TTL。
- 当前类允许关闭 TLS 校验。迁移到 JDK client 后必须在使用 HTTPS 的测试环境验证自签名证书和
  hostname 行为；生产环境不应依赖 trust-all。
- JDK `HttpClient` 的连接池指标不像 Apache HttpClient 那样直接暴露。本文目标是共享连接池和
  限制上游并发，不声称已获得池内连接等待指标。
- 本改造不会解决调用方自己的 Redis 业务缓存、线程池或 DTO 可变性；它只保证
  `GpLinkServiceImpl` 内不缓存实时/历史响应，且元数据 snapshot 不被调用方反向修改。
