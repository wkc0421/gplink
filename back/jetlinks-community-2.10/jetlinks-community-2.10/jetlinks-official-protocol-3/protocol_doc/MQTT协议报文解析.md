本文章将指导你编写自定义协议包，处理设备通过MQTT协议与平台连接的场景

# 前提条件
1. 开发工具，本例使用[IDEA](https://www.jetbrains.com/idea/)
2. [创建协议工程](https://hanta.yuque.com/px7kg1/nn1gdr/hg5r4psvn9fdsv1c) 或者 拉取[官方协议](https://github.com/jetlinks/jetlinks-official-protocol)(进行修改)

# 操作步骤
## 创建mqtt编解码类
创建`MqttDeviceMessageCodec`并实现`DeviceMessageCodec`接口。

<img src="https://cdn.nlark.com/yuque/0/2023/png/34705917/1681116949081-720dd2ae-b7c8-4626-bcae-ce4a81996b58.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_66%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="3000" title="创建类实现DeviceMessageCodec" crop="0,0,1,1" id="ua6141e78" class="ne-image">

### 编写解码逻辑
:::info
**<font style="color:#2F4BDA;">🌏</font>****<font style="color:#2F4BDA;"> 说明</font>**

在服务器收到设备或者网络组件中发来的消息时，会调用协议包中的`decode`方法来进行解码， 将数据转为平台的统一消息`org.jetlinks.core.message.DeviceMessage`，消息方向为设备-->平台。

:::

编写`decode`方法。<img src="https://cdn.nlark.com/yuque/0/2024/png/21732731/1706161345059-4f88b99b-c260-41b5-86b7-60799863287b.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_55%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1918" title="" crop="0,0,1,1" id="ue9a0d94b" class="ne-image">

```java
  public Flux<DeviceMessage> decode(@Nonnull MessageDecodeContext context) {
        MqttMessage message = (MqttMessage) context.getMessage();
        byte[] payload = message.payloadAsBytes();

        return TopicMessageCodec
                .decode(mapper, TopicMessageCodec.removeProductPath(message.getTopic()), payload)
                //如果不能直接解码，可能是其他设备功能
                .switchIfEmpty(FunctionalTopicHandlers
                                       .handle(context.getDevice(),
                                               message.getTopic().split("/"),
                                               payload,
                                               mapper,
                                               reply -> doReply(context, reply)))
                ;

    }
```

#### 返回多条消息
```java
	public Flux<DeviceMessage> decode(MessageDecodeContext context){
	
		EncodedMessage message = context.getMessage();
	 	byte[] payload = message.payloadAsBytes();
		
		List<DeviceMessage> messages = doDecode(payload);
		
		return Flux.fromIterable(messages);
	}
```

#### 上报响应回复
```java
 public Mono<DeviceMessage> decode(MessageDecodeContext context) {
        EncodedMessage message = context.getMessage();
        byte[] payload = message.payloadAsBytes();//上报的数据     
        DeviceMessage decodeMessage = doEncode(payload); //解码      
        EncodedMessage msg = createReplyMessage(); //构造回复     
        return ((FromDeviceMessageContext) context)
                .getSession()
                .send(msg)
                .thenReturn(decodeMessage);
    }

public EncodedMessage createReplyMessage() {
	return SimpleMqttMessage
			.builder()
			.topic("topic")
			.payload(Unpooled.wrappedBuffer("响应".getBytes()))
			.build();
}
```

### 编写编码逻辑
:::info
**<font style="color:#2F4BDA;">🌏</font>****<font style="color:#2F4BDA;"> 说明</font>**

将消息进行编码，用于发送到设备端。平台在发送指令给设备时，会调用协议包中的`encode`方法，将平台消息`org.jetlinks.core.message.DeviceMessage`转为设备能理解的消息`EncodedMessage`，消息方向为平台-->设备。

:::

编写`encode`方法。<img src="https://cdn.nlark.com/yuque/0/2024/png/21732731/1706162283589-549b1d58-0c4a-4087-87dd-ff404931ebbd.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_55%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1928" title="" crop="0,0,1,1" id="u281f5fdb" class="ne-image">

```java
{
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
                                .topic("/".concat(productId).concat(convertResult.getTopic()))
                                .payloadType(MessagePayloadType.JSON)
                                .payload(Unpooled.wrappedBuffer(convertResult.getPayload()))
                                .build());
            } else {
                return Mono.empty();
            }
        });
    }
```

#### 多条指令下发
```java
public Flux<EncodedMessage> encode(MessageEncodeContext context){        
	List<EncodedMessage> messages = doEncode(context);
	return Flux.fromIterable(messages); 
}
```

#### 忽略发送给设备,直接返回结果给指令发送者
```java
public Mono<EncodedMessage> encode(MessageEncodeContext context){
	DeviceMessage message = (DeviceMessage)context.getMessage();
	//构造回复消息
	DeviceMessage replyMsg =   ((RepayableDeviceMessage)deviceMessage).newReply(); 
	
	return context
		.reply(replyMsg) //返回结果给指令发送者
		.then(Mono.empty())

}
```

## 将编解码器注册到协议中
编写类`MyProtocolSupportProvider`,此处只是将编解码类注册至协议中，实际应用过程需要将Topic注册至协议路由管理器内。参考[添加Topic至协议路由管理器](#gwQ1Q)

<img src="https://cdn.nlark.com/yuque/0/2023/png/34705917/1681117105253-d2e8e089-2921-4b68-b66f-5474a6d40371.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_66%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="3000" title="编解码器注册到协议中" crop="0,0,1,1" id="uc4ae0741" class="ne-image">

## 添加Topic至协议路由管理器
:::warning
**<font style="color:#ECAA04;">💡</font>****<font style="color:#ECAA04;"> 注意</font>**

平台对MQTT、HTTP、WebSocket方式接入需要在`Provider`入口类配置routes扫描，如下图67行。

+ 可以通过`{}`来指定动态`topic`, 如 `/function/{functionId}`，订阅时会自动转换为mqtt的

`topic/functoin/+`

+ 如果要使用`#`则可以这样定义: `/function/{#functionId}`，订阅时会自动转换为mqtt的`topic/functoin/#`。

:::

<img src="https://cdn.nlark.com/yuque/0/2023/png/34705917/1681117536620-9d652a2d-a67a-434a-96a7-642a4ad78a4c.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_66%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="3000" title="定义Topic" crop="0,0,1,1" id="XJ71a" class="ne-image">

```java
support.addRoutes(DefaultTransport.MQTT, Arrays.asList(
        Route.mqtt("/properties/report")
                .group("属性相关")
                .description("上报属性")
                .upstream(true)
                .build(),
        Route.mqtt("/function/{functionId}")
                .group("功能相关")
                .description("平台下发功能调用指令")
                .downstream(true)
                .build()
));
```

## 编写MQTT认证规则
通过`mqtt`直连接入时，需要先对`mqtt`请求进行认证。在`mqtt`客户端发送`CONNECT`报文时，平台会根据

`clientId`获取对应的设备实例，然后调用此设备的协议包进行认证，认证通过后建立连接。

<img src="https://cdn.nlark.com/yuque/0/2023/png/34705917/1681117183695-6b7db72d-9281-4288-8e77-a03897350590.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_66%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="3000" title="编写MQTT认证规则" crop="0,0,1,1" id="GtdXU" class="ne-image">

```java
//MQTT 认证
support.addAuthenticator(DefaultTransport.MQTT, (request, device) -> {
MqttAuthenticationRequest mqttRequest = ((MqttAuthenticationRequest) request);
return device
        .getConfigs("username", "password")
        .flatMap(values -> {
            String username = values.getValue("username").map(Value::asString).orElse(null);
            String password = values.getValue("password").map(Value::asString).orElse(null);
            if (mqttRequest.getUsername().equals(username) && mqttRequest
                    .getPassword()
                    .equals(password)) {
                return Mono.just(AuthenticationResponse.success());
            } else {
                return Mono.just(AuthenticationResponse.error(400, "密码错误"));
            }
        });
});
```

:::info
**<font style="color:#2F4BDA;">🌏</font>****<font style="color:#2F4BDA;"> 说明</font>**

`device.getConfigs("xx")`：先从设备注册中心中设备信息内获取配置，如果不存在则从产品中获取。 如果只从设备中获取可以使用`device.getSelfConfigs("xx")`。

:::

## 定义MQTT配置描述
编写完成认证规则后，需要获取设备的配置信息`username`和`password`。通过定义`mqtt`配置描述来告诉平台，使用该协议的产品或者设备需要进行相应的配置。 在对应的产品或者设备界面才会出现配置项目输入<font style="color:rgb(44, 62, 80);">。</font>

<img src="https://cdn.nlark.com/yuque/0/2023/png/34705917/1681117337782-9703fce1-5c10-40d3-a8ce-c50943894c29.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_66%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="3000" title="编写MQTT配置定义" crop="0,0,1,1" id="iIEcq" class="ne-image">

```java
//设置配置定义信息
support.addConfigMetadata(DefaultTransport.MQTT,
    new DefaultConfigMetadata("MQTT认证配置", "MQTT接入时使用的认证配置")
            //产品和设备都可以配置
            .scope(DeviceConfigScope.device,DeviceConfigScope.product)
            .add("username", "username", "MQTT用户名", StringType.GLOBAL)
            .add("password", "password", "MQTT密码", PasswordType.GLOBAL)
);
```

## 打包并发布到平台
执行`mvn package`打包并将生成的jar包上传到平台即可。

<img src="https://cdn.nlark.com/yuque/0/2023/png/34705917/1681117608960-a9c19040-a927-4c50-9fd5-2983614ed2ec.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_66%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="3000" title="使用Maven命令打包" crop="0,0,1,1" id="ua5023573" class="ne-image">



---

上一篇：[创建协议工程](https://hanta.yuque.com/px7kg1/nn1gdr/hg5r4psvn9fdsv1c)

下一篇：[自定义Topic通信](https://hanta.yuque.com/px7kg1/nn1gdr/ga4rb6idegoy6nkx)