本文章将指导你编写自定义协议包，处理设备通过TCP协议与平台连接的场景

# 前提条件
1. 开发工具，本例使用[IDEA](https://www.jetbrains.com/idea/)
2. [创建协议工程](https://hanta.yuque.com/px7kg1/nn1gdr/hg5r4psvn9fdsv1c) 或者 拉取[官方协议](https://github.com/jetlinks/jetlinks-official-protocol)(进行修改)

# 操作步骤
## 创建TCP编解码类
创建`TcpDeviceMessageCodec`并实现`DeviceMessageCodec`接口。

<img src="https://cdn.nlark.com/yuque/0/2023/png/35443755/1684483792707-69ec84e8-cf2a-4ac6-bed4-eec00284d0b6.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_55%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1921" title="创建类实现DeviceMessageCodec" crop="0,0,1,1" id="uce62045b" class="ne-image">

### 编写解码逻辑
:::info
**<font style="color:#2F4BDA;">🌏</font>****<font style="color:#2F4BDA;"> 说明</font>**

在服务器收到设备或者网络组件中发来的消息时，会调用协议包中的`decode`方法来进行解码， 将数据逐字节解析后转为平台的统一消息`org.jetlinks.core.message.DeviceMessage`，消息方向为设备-->平台。

:::

编写`decode`方法。

<img src="https://cdn.nlark.com/yuque/0/2023/png/35443755/1684487289553-15c28642-d33f-4b83-b6e1-c07ac7e0c06d.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_55%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1921" title="编写decode方法" crop="0,0,1,1" id="u9dc1fd65" class="ne-image">

`decode`方法举例：

```java
  //解码并返回一个消息
  public Mono<DeviceMessage> decode(MessageDecodeContext context){
 
   		EncodedMessage message = context.getMessage();
   		byte[] payload = message.payloadAsBytes();//上报的数据
 
   		DeviceMessage message = doDecode(payload);
 
   		return Mono.just(message);
  }
 
  //解码并返回多个消息
  public Flux<DeviceMessage> decode(MessageDecodeContext context){
 
   		EncodedMessage message = context.getMessage();
        byte[] payload = message.payloadAsBytes();//上报的数据
     
        List<DeviceMessage> messages = doDecode(payload);
     
        return Flux.fromIterable(messages);
  }
 
  //解码,回复设备并返回一个消息
  public Mono<DeviceMessage> decode(MessageDecodeContext context){
 
   EncodedMessage message = context.getMessage();
   byte[] payload = message.payloadAsBytes();//上报的数据
 
   DeviceMessage message = doDecode(payload); //解码
 
   FromDeviceMessageContext ctx = (FromDeviceMessageContext)context;
 
   EncodedMessage msg = createReplyMessage(); //构造回复
 
   return ctx
      .getSession()
      .send(msg) //发送回复
      .thenReturn(message);
  }
```

### 编写编码逻辑
:::info
**<font style="color:#2F4BDA;">🌏</font>****<font style="color:#2F4BDA;"> 说明</font>**

将消息进行编码，用于发送到设备端。平台在发送指令给设备时，会调用协议包中的`encode`方法，将平台消息`org.jetlinks.core.message.DeviceMessage`转为设备能理解的消息`EncodedMessage`，消息方向为平台-->设备。

:::

编写`encode`方法。

<img src="https://cdn.nlark.com/yuque/0/2023/png/35443755/1684721508432-96075d31-0c8e-4f4b-bffe-c16aa320dc40.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_55%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1921" title="编写encode方法" crop="0,0,1,1" id="u32a6f9d7" class="ne-image">

`encode`方法举例：

```java
//返回单个消息给设备,多个使用Flux<EncodedMessage>作为返回值
public Mono<EncodedMessage> encode(MessageEncodeContext context){

	return Mono.just(doEncode(context.getMessage()));

}

//忽略发送给设备,直接返回结果给指令发送者
public Mono<EncodedMessage> encode(MessageEncodeContext context){
DeviceMessage message = (DeviceMessage)context.getMessage();

return context
		.reply(handleMessage(message)) //返回结果给指令发送者
		.then(Mono.empty())
}
```

## 使用策略模式解析/封装
建议使用策略模式来定义功能码，以及不同功能码对应的解析规则。如：使用枚举来定义功能码。

避免进行`array copy`，应该使用偏移量直接处理报文。

常见场景算法实践：

1. 使用枚举来处理不同类型的数据

伪代码如下:

```java
@AllArgsConstructor
@Getter
public enum UpstreamCommand {
    //注册命令
    register((byte) 0x00, RegisterMessage::decode),
    //数据命令
    data((byte) 0x02, DataMessage::decode),
  
    byte code;

    BiFunction<byte[], Integer, ? extends MyDeviceMessage> decoder;

    public static UpstreamCommand of(byte code) {
        for (UpstreamCommand value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new UnsupportedOperationException("不支持的命令:0x" + 
                                                Hex.encodeHexString(new byte[]{data}));
    }
    
    //调用此方法解码数据
    public static MyDeviceMessage decode(byte[] body) {
        //业务流水号
        int msgId = BytesUtils.beToInt(body, 0, 2); //2字节小端转int
       
        //命令
        UpstreamCommand command = of(body[4]);
 
        return command.getDecoder().apply(body, 5);
    }
}
```

2. 使用二进制位来表示状态，0表示正常，1表示异常：

使用枚举定义二进制位表示的含义，使用位运算来判断对应数据是哪一位

```java
@AllArgsConstructor
@Getter
public enum DataType{
    OK(-9999,"正常"),
    Bit0(0,"测试"),
    Bit1(1,"火警"),
    Bit2(2,"故障")
    //....
    ;
    private int bit;
    private String text;

    public static DataType of(long value) {
    if (value == 0) {
        return OK;
    }
    for (DataType type : values()) {
        if ((value & (1 << type.bit)) != 0) {
            //数据位支持多选的话,装到集合里即可
            return partState;
        }
    }
    throw new UnsupportedOperationException("不支持的状态:" + value);
 }
}
```

:::warning
**<font style="color:#ECAA04;">💡</font>****<font style="color:#ECAA04;"> 注意</font>**

应该将对报文处理的类封装为独立的类，在开发过程中，可以使用单元测试验证代码处理逻辑及返回结果是否正确。 

开发者应避免直接在decode()和encode()方法里编写数据处理逻辑。

:::

## 将编解码器注册到协议中
编写类`MyProtocolSupportProvider`，加入自定义的编解码器。

<img src="https://cdn.nlark.com/yuque/0/2023/png/35443755/1684492586329-fc860c8f-e269-417c-9a0f-cedec74cc943.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_55%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1921" title="" crop="0,0,1,1" id="u560fdf0e" class="ne-image">

## 编写TCP认证规则
:::info
**<font style="color:#2F4BDA;">🌏</font>****<font style="color:#2F4BDA;"> 说明</font>**

TCP认证只能在接收到数据后从字节流中逐字节解析得到认证信息，是否需要认证请根据真实设备所提供的文档编写协议。

:::

1. 定义TCP配置，添加认证字段。

<img src="https://cdn.nlark.com/yuque/0/2023/png/35443755/1684721016910-ee59b4fb-f6c2-4614-84fa-b17d2b340e02.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_55%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1921" title="" crop="0,0,1,1" id="u5f0fca0f" class="ne-image">

2. 在`TcpDeviceMessageCodec`的`decode`方法中编写认证逻辑。

```java
	@Nonnull
    @Override
    public Publisher<? extends Message> decode(@Nonnull MessageDecodeContext context) {
        return Mono.defer(() -> {
            //获取发送的二进制数据流,下面开始逐字解析
            ByteBuf payload = context.getMessage().getPayload();
            //起始位 固定为@@
            payload.readShort();
            //读取token
            String token = readString(payload);
            //设备id
            String deviceId = readString(payload);
            return context
                    .getDevice(deviceId)
                    //获取设备后判断Token是否对应
                    .flatMap(device ->
                                     device.getConfig("Token")
                                           .flatMap(deviceToken -> {
                                               if (Objects.equals(deviceToken.asString(), token)) {
                                                   //认证通过，设备上线
                                                   DeviceOnlineMessage onlineMessage = new DeviceOnlineMessage();
                                                   onlineMessage.setDeviceId(deviceId);
                                                   return Mono.just(onlineMessage);
                                               } else {
                                                   return Mono.error(new RuntimeException("Token认证失败"));
                                               }
                                           }))
                    //根据设备id没有找到指定设备
                    .switchIfEmpty(...)
        });
    }

    public static String readString(ByteBuf buf) {
        int len = buf.readByte();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
```

## 打包并发布到平台
执行`mvn package`打包并将生成的jar包上传到平台即可。

<img src="https://cdn.nlark.com/yuque/0/2023/png/35443755/1684722447950-5a294880-44fe-432e-8d37-5d16d75b9b17.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_55%2Ctext_SmV0TGlua3M%3D%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10" width="1921" title="" crop="0,0,1,1" id="uc03333fc" class="ne-image">



---

上一篇：[自定义Topic通信](https://hanta.yuque.com/px7kg1/nn1gdr/ga4rb6idegoy6nkx)

下一篇：[设备身份认证](https://hanta.yuque.com/px7kg1/nn1gdr/snxiwbk5yo2otpp2)