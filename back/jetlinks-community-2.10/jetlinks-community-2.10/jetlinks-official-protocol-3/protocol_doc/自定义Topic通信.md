JetLinks平台支持基于MQTT、UDP、HTTP、TCP透传等协议通过自定义协议包的方式，解析不同厂家、不同设备上报的数据，用户可以在协议包中自定义Topic，设备则通过自定义Topic上报消息。本文使用MQTT模拟器模拟设备举例说明如何使用自定义Topic与物联网平台通信。

# 前提条件
1. 已创建好协议工程，参考：[创建协议工程](https://hanta.yuque.com/px7kg1/nn1gdr/hg5r4psvn9fdsv1c) 或者 拉取[官方协议](https://github.com/jetlinks/jetlinks-official-protocol)(进行修改)
2. 已编写完成协议功能，参考：[MQTT协议解析](https://hanta.yuque.com/px7kg1/nn1gdr/fov65td7d0mlynn9)

# 操作步骤
## 在协议包中定义topic
创建枚举类`TopicMessageCodec`。用于定义Topic信息。如下方代码所示。

```java
package com.example.custom.protocol;

public enum TopicMessageCodec {
    //属性上报
    //方向：设备->平台
    customReportProperty("/custom/protocol/properties/report",
                   ReportPropertyMessage.class,
                   route -> route
                           .upstream(true)
                           .downstream(false)
                           .group("属性上报")
                           .description("上报物模型属性数据")
                           .example("{\"properties\":{\"属性ID\":\"属性值\"}}")),

    //调用功能
    //方向：平台->设备
    customFunctionInvoke("/custom/protocol/function/invoke",
                   FunctionInvokeMessage.class,
                   route -> route
                           .upstream(false)
                           .downstream(true)
                           .group("调用功能")
                           .description("平台下发功能调用指令")
                           .example("{\"messageId\":\"消息ID,回复时需要一致.\"," +
                                            "\"functionId\":\"功能标识\"," +
                                            "\"inputs\":[{\"name\":\"参数名\",\"value\":\"参数值\"}]}"));


    private final String[] pattern;
    private final MqttRoute route;
    private final Class<? extends DeviceMessage> type;



    TopicMessageCodec(String topic,
                      Class<? extends DeviceMessage> type,
                      Function<MqttRoute.Builder, MqttRoute.Builder> routeCustom) {
        this.pattern = topic.split("/");
        this.type = type;
        this.route = routeCustom.apply(toRoute()).build();
    }

    TopicMessageCodec(String topic, Class<? extends DeviceMessage> type) {

        this.pattern = topic.split("/");
        this.route = null;
        this.type = type;
    }

    @SneakyThrows
    private MqttRoute.Builder toRoute() {
        String[] topics = new String[pattern.length];
        System.arraycopy(pattern, 0, topics, 0, pattern.length);
        topics[0] = "{productId:产品ID}";
        topics[1] = "{deviceId:设备ID}";
        transMqttTopic(topics);
        StringJoiner joiner = new StringJoiner("/", "/", "");
        for (String topic : topics) {
            joiner.add(topic);
        }
        return MqttRoute
                .builder(joiner.toString())
                .qos(1);
    }

    protected void transMqttTopic(String[] topic) {

    }

}

```

:::danger
**<font style="color:#DF2A3F;">‼️</font>****<font style="color:#DF2A3F;"> 警告</font>**

若开发者自定义主题只有一级，枚举类`TopicMessageCodec`中自定义主题需去掉`/`并且修改`toRoute()`方法，注释方法中 topics[0]和 topics[1]。  
详细修改参考下方示例。

:::

```java
//1.主题：  
    //属性上报
    //方向：设备->平台
    customReportProperty("custom",
                   ReportPropertyMessage.class,
                   route -> route
                           .upstream(true)
                           .downstream(false)
                           .group("属性上报")
                           .description("上报物模型属性数据")
                           .example("{\"properties\":{\"属性ID\":\"属性值\"}}")),
//2.修改 toRoute()方法
    @SneakyThrows
    private MqttRoute.Builder toRoute() {
        String[] topics = new String[pattern.length];
        System.arraycopy(pattern, 0, topics, 0, pattern.length);
        // topics[0] = "{productId:产品ID}";
        // topics[1] = "{deviceId:设备ID}";
        transMqttTopic(topics);
        StringJoiner joiner = new StringJoiner("/", "/", "");
        for (String topic : topics) {
            joiner.add(topic);
        }
        return MqttRoute
                .builder(joiner.toString())
                .qos(1);
    }
```

 属性上报说明。

```java
    //属性上报
    //方向：设备->平台
    customReportProperty("/custom/protocol/properties/report", //上报主题
                   ReportPropertyMessage.class,  //上报属性消息转换为平台的消息类型
                   route -> route  //function类型参数。根据toRoute方法构建传入MqttRoute参数route，
                                   //返回枚举值中声明的route。
                           .upstream(true)  //是否上行
                           .downstream(false)  //是否下行
                           .group("属性上报")   //分组信息
                           .description("上报物模型属性数据")  //描述信息
                           .example("{\"properties\":{\"属性ID\":\"属性值\"}}"))  //示例
    

```

功能调用主题说明。

```java
    //调用功能
    //方向：平台->设备
    customFunctionInvoke("/custom/protocol/function/invoke",  //下发的主题
                   FunctionInvokeMessage.class,  //功能下发转换为平台的消息类型
                   route -> route  //function类型参数。根据toRoute方法构建传入MqttRoute参数route，
                                   //返回枚举值中声明的route。
                           .upstream(false)  // 是否上行
                           .downstream(true)  // 是否下行
                           .group("调用功能") // 分组信息
                           .description("平台下发功能调用指令")  //描述信息
                           .example("{\"messageId\":\"消息ID,回复时需要一致.\"," +  //示例
                                            "\"functionId\":\"功能标识\"," +
                                            "\"inputs\":[{\"name\":\"参数名\",\"value\":\"参数值\"}]}"));

```

### 编写Topic匹配规则
使用属性上报、功能调用等Topic通信时，需要与协议包`TopicMessageCodec`类中定义的Topic相匹配才能进行正确通信。用户需要为平台和设备的通信建立匹配规则。如下方代码所示。

#### 设备上报数据时Topic匹配规则
```java
package com.example.custom.protocol;

public enum TopicMessageCodec {

   //用于解码消息
   public static Flux<DeviceMessage> decode(ObjectMapper mapper, String[] topics, byte[] payload) {
        return Mono
                .justOrEmpty(fromTopic(topics))
                .flatMapMany(topicMessageCodec -> topicMessageCodec.doDecode(mapper, topics, payload));
    }
    
   //已给出ObjectMapper工具类代码示例。
   Publisher<DeviceMessage> doDecode(ObjectMapper mapper, String[] topic, byte[] payload) {
    return Mono
            .fromCallable(() -> {
                DeviceMessage message = mapper.readValue(payload, type);
                FastBeanCopier.copy(Collections.singletonMap("deviceId", topic[1]), message);

                return message;
            });
    }
    
    public static String[] removeProductPath(String topic) {
          
        if (!topic.startsWith("/")) {
            topic = "/" + topic;
        }
        String[] topics = topic.split("/");

        return topics;
    }
    
}
```

#### 平台下发数据时Topic匹配规则
```java
package com.example.custom.protocol;

public enum TopicMessageCodec {

 //用于编码消息
  public static TopicPayload encode(ObjectMapper mapper, DeviceMessage message) {

        return fromMessage(message)
                .orElseThrow(() -> new UnsupportedOperationException("unsupported message:" + message.getMessageType()))
                .doEncode(mapper, message);
    }

    static Optional<TopicMessageCodec> fromMessage(DeviceMessage message) {
        for (TopicMessageCodec value : values()) {
            if (value.type == message.getClass()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    @SneakyThrows
    TopicPayload doEncode(ObjectMapper mapper, DeviceMessage message) {
        String[] topics = Arrays.copyOf(pattern, pattern.length);
        return doEncode(mapper, topics, message);
    }

    @SneakyThrows
    TopicPayload doEncode(ObjectMapper mapper, String[] topics, DeviceMessage message) {
        return TopicPayload.of(String.join("/", topics), Payload.of(mapper.writeValueAsBytes(message)));
    }
}
```

```java
package com.example.customp.rotocol;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor(staticName = "of")
@NoArgsConstructor
public class TopicPayload {

    private String topic;

    private byte[] payload;
}

```

## 改写MqttDeviceMessageCodec编解码类
改写`MqttDeviceMessageCodec`类中`decode`和`encode`方法。如下方代码所示。

```java
package com.example.custom.protocol;


public class MqttDeviceMessageCodec implements DeviceMessageCodec {

    private final Transport transport;

    private final ObjectMapper mapper;

    @Override
    public Transport getSupportTransport() {
        return DefaultTransport.MQTT;
    }

    public MqttDeviceMessageCodec() {
        this.mapper = ObjectMappers.JSON_MAPPER;
        this.transport = DefaultTransport.MQTT;
    }

    public MqttDeviceMessageCodec(Transport transport) {
        this.transport = transport;
        this.mapper = ObjectMappers.JSON_MAPPER;
    }

    @Nonnull
    @Override
    public Publisher<? extends Message> decode(@Nonnull MessageDecodeContext context) {
        FromDeviceMessageContext ctx = (FromDeviceMessageContext) context;
        MqttMessage message = (MqttMessage) ctx.getMessage();
        String topic = message.getTopic();
        byte[] payload = message.payloadAsBytes();

        return TopicMessageCodec
                .decode(mapper, TopicMessageCodec.removeProductPath(message.getTopic()), payload)
                .switchIfEmpty(Flux.empty());

    }

    @Nonnull
    @Override
    public Mono<MqttMessage> encode(@Nonnull MessageEncodeContext context) {
        return Mono.defer(() -> {
            Message message = context.getMessage();

            if (message instanceof DisconnectDeviceMessage) {
                return ((ToDeviceMessageContext) context)
                        .disconnect()
                        .then(Mono.empty());
            }

            if (message instanceof DeviceMessage) {
                DeviceMessage deviceMessage = ((DeviceMessage) message);

                TopicPayload convertResult = TopicMessageCodec.encode(mapper, deviceMessage);
                if (convertResult == null) {
                    return Mono.empty();
                }
                return Mono
                        .justOrEmpty(deviceMessage.getHeader("productId").map(String::valueOf))
                        .switchIfEmpty(context.getDevice(deviceMessage.getDeviceId())
                                              .flatMap(device -> device.getSelfConfig(DeviceConfigKey.productId))
                        )
                        .defaultIfEmpty("null")
                        .map(productId -> SimpleMqttMessage
                                .builder()
                                .clientId(deviceMessage.getDeviceId())
                                .topic(convertResult.getTopic())
                                .payloadType(MessagePayloadType.JSON)
                                .payload(Unpooled.wrappedBuffer(convertResult.getPayload()))
                                .build());
            } else {
                return Mono.empty();
            }
        });
    }
}

```

## 打包并发布到平台
参考：MQTT协议解析文档中[打包并发布到平台](https://hanta.yuque.com/px7kg1/nn1gdr/fov65td7d0mlynn9#XMi49)

## JetLinks预配置
请参考[MQTT直连接入](https://hanta.yuque.com/px7kg1/yfac2l/svxnz51wtqo0f2rv)文档内的[创建产品](https://hanta.yuque.com/px7kg1/yfac2l/ikiyz2ao2kagne1g#KP8uB)、[配置产品物模型](https://hanta.yuque.com/px7kg1/yfac2l/ikiyz2ao2kagne1g#yHVPL)以及[创建设备](https://hanta.yuque.com/px7kg1/yfac2l/ikiyz2ao2kagne1g#ilMyc)等步骤，配置接入JetLinks物联网平台的产品、设备、物模型等数据。

# 后续步骤
## 使用MQTTX模拟设备接入
MQTTX模拟器和平台正常连接，此时平台设备处于在线状态。

<img src="https://cdn.nlark.com/yuque/0/2023/png/22419630/1684827993698-0a21b563-8ae4-46ec-ad18-3a8f3d10174e.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_53%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1665.7777777777778" title="设备上线" crop="0,0,1,1" id="ub839c14a" class="ne-image">

### 使用自定义协议包中Topic上报属性
在模拟器中使用自定义Topic上报`temperature`属性为38.5，平台侧同步该属性值。

<img src="https://cdn.nlark.com/yuque/0/2023/png/22419630/1684828080877-5b893e12-34b0-4292-8b85-60b708d957bc.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_54%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1685.3333333333333" title="属性上报" crop="0,0,1,1" id="u0ae6269e" class="ne-image">



### 使用自定义协议包中Topic功能调用
**登录JetLinks平台>点击设备>点击设备功能>选择具体功能>点击执行**，平台下发功能调用消息到设备，MQTTX模拟器处接收功能下发消息。如下图所示。

<img src="https://cdn.nlark.com/yuque/0/2023/png/22419630/1684828440603-401f7f30-6677-4973-8e67-4ae3262a7155.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_54%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1680.888888888889" title="功能下发" crop="0,0,1,1" id="ufe169c57" class="ne-image">



---

上一篇：[MQTT协议解析](https://hanta.yuque.com/px7kg1/nn1gdr/fov65td7d0mlynn9)

下一篇：[TCP协议解析](https://hanta.yuque.com/px7kg1/nn1gdr/uo03dpnnoishhim6)