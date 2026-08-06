/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetlinks.community.network.mqtt.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.ReferenceCountUtil;
import io.vertx.core.buffer.Buffer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkType;
import org.jetlinks.core.message.codec.MqttMessage;
import org.jetlinks.core.message.codec.SimpleMqttMessage;
import org.jetlinks.core.topic.Topic;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 使用Vertx，MQTT Client。
 *
 * @author zhouhao
 * @since 1.0
 */
@Slf4j
public class VertxMqttClient implements MqttClient {

    @Getter
    private io.vertx.mqtt.MqttClient client;

    private final Topic<Tuple3<String, FluxSink<MqttMessage>, Integer>> subscriber = Topic.createRoot();

    private final String id;

    private volatile boolean loading;

    private final List<Runnable> loadSuccessListener = new CopyOnWriteArrayList<>();

    private final List<PendingPublish> pendingPublishes = new CopyOnWriteArrayList<>();

    //订阅前缀
    @Setter
    private String topicPrefix;

    public void setLoading(boolean loading) {
        this.loading = loading;
        if (!loading) {
            List<Runnable> listeners = List.copyOf(loadSuccessListener);
            loadSuccessListener.clear();
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (Throwable error) {
                    log.warn("execute mqtt client [{}] load listener error", id, error);
                }
            }
            List<PendingPublish> publishes = List.copyOf(pendingPublishes);
            pendingPublishes.clear();
            for (PendingPublish publish : publishes) {
                publish.start();
            }
        }
    }

    public boolean isLoading() {
        return loading;
    }

    public VertxMqttClient(String id) {
        this.id = id;
    }

    public void setClient(io.vertx.mqtt.MqttClient client) {
        if (this.client != null && this.client != client) {
            try {
                this.client.disconnect();
            } catch (Exception ignore) {

            }
        }
        this.client = client;
        client
            .closeHandler(nil -> log.debug("mqtt client [{}] closed", id))
            .publishHandler(msg -> {
                try {
                    MqttMessage mqttMessage = SimpleMqttMessage
                        .builder()
                        .messageId(msg.messageId())
                        .topic(msg.topicName())
                        .payload(msg.payload().getByteBuf())
                        .dup(msg.isDup())
                        .retain(msg.isRetain())
                        .qosLevel(msg.qosLevel().value())
                        .properties(msg.properties())
                        .build();
                    log.debug("handle mqtt message \n{}", mqttMessage);
                    subscriber
                        .findTopic(msg.topicName().replace("#", "**").replace("+", "*"))
                        .flatMapIterable(Topic::getSubscribers)
                        .subscribe(sink -> {
                            try {
                                sink.getT2().next(mqttMessage);
                            } catch (Exception e) {
                                log.error("handle mqtt message error", e);
                            }
                        });
                } catch (Throwable e) {
                    log.error("handle mqtt message error", e);
                }
            });
        if (loading) {
            loadSuccessListener.add(this::reSubscribe);
        } else if (isAlive()) {
            reSubscribe();
        }

    }

    private void reSubscribe() {
        subscriber
            .getAllSubscriber()
            .filter(topic -> !topic.getSubscribers().isEmpty())
            .collectMap(topic -> getCompleteTopic(convertMqttTopic(topic.getSubscribers().iterator().next().getT1())),
                        topic -> topic.getSubscribers().iterator().next().getT3())
            .filter(MapUtils::isNotEmpty)
            .subscribe(topics -> {
                log.debug("subscribe mqtt topic {}", topics);
                client.subscribe(topics);
            });
    }

    private String convertMqttTopic(String topic) {
        return topic.replace("**", "#").replace("*", "+");
    }

    protected String parseTopic(String topic) {
        //适配emqx共享订阅
        if (topic.startsWith("$share")) {
            topic = Stream.of(topic.split("/"))
                          .skip(2)
                          .collect(Collectors.joining("/", "/", ""));
        } else if (topic.startsWith("$queue")) {
            topic = topic.substring(6);
        }
        if (topic.startsWith("//")) {
            return topic.substring(1);
        }
        return topic;
    }

    //获取完整的topic
    protected String getCompleteTopic(String topic) {
        if (StringUtils.isEmpty(topicPrefix)) {
            return topic;
        }
        return topicPrefix.concat(topic);
    }

    @Override
    public Flux<MqttMessage> subscribe(List<String> topics, int qos) {
        return Flux.create(sink -> {

            Disposable.Composite composite = Disposables.composite();

            for (String topic : topics) {
                String realTopic = parseTopic(topic);
                String completeTopic = getCompleteTopic(topic);

                Topic<Tuple3<String, FluxSink<MqttMessage>, Integer>> sinkTopic = subscriber
                    .append(realTopic
                                .replace("#", "**")
                                .replace("+", "*"));

                Tuple3<String, FluxSink<MqttMessage>, Integer> topicQos = Tuples.of(topic, sink, qos);

                boolean first = sinkTopic.getSubscribers().isEmpty();
                sinkTopic.subscribe(topicQos);
                composite.add(() -> {
                    if (!sinkTopic.unsubscribe(topicQos).isEmpty() && isAlive() && sinkTopic.getSubscribers().isEmpty()) {
                        client.unsubscribe(convertMqttTopic(completeTopic), result -> {
                            if (result.succeeded()) {
                                log.debug("unsubscribe mqtt topic {}", completeTopic);
                            } else {
                                log.debug("unsubscribe mqtt topic {} error", completeTopic, result.cause());
                            }
                        });
                    }
                });

                //首次订阅
                if (isAlive() && first) {
                    log.debug("subscribe mqtt topic {}", completeTopic);
                    client.subscribe(convertMqttTopic(completeTopic), qos, result -> {
                        if (!result.succeeded()) {
                            sink.error(result.cause());
                        }
                    });
                }
            }

            sink.onDispose(composite);

        });
    }

    private Mono<Void> doPublish(MqttMessage message) {
        return Mono.create((sink) -> {
            ByteBuf payload = message.getPayload();
            Buffer buffer;
            try {
                // The MQTT message transfers ownership of its reference-counted payload to this method.
                // Copy it into a Vert.x-owned byte array so a missing publish callback cannot retain ByteBuf.
                buffer = Buffer.buffer(ByteBufUtil.getBytes(payload));
            } catch (Throwable error) {
                sink.error(error);
                return;
            } finally {
                ReferenceCountUtil.safeRelease(payload);
            }
            try {
                client.publish(message.getTopic(),
                               buffer,
                               MqttQoS.valueOf(message.getQosLevel()),
                               message.isDup(),
                               message.isRetain(),
                               result -> {
                                   if (result.succeeded()) {
                                       log.info("publish mqtt [{}] message success: {}", client.clientId(), message);
                                       sink.success();
                                   } else {
                                       log.info("publish mqtt [{}] message error : {}", client.clientId(), message, result.cause());
                                       sink.error(result.cause());
                                   }
                               });
            } catch (Throwable e) {
                sink.error(e);
            }
        });
    }

    @Override
    public Mono<Void> publish(MqttMessage message) {
        if (loading) {
            return Mono.create(sink -> {
                PendingPublish pending = new PendingPublish(message, sink);
                pendingPublishes.add(pending);
                sink.onCancel(pending::cancel);
                // Avoid losing a publish when loading changes between the initial check and queue insertion.
                if (!loading && pendingPublishes.remove(pending)) {
                    pending.start();
                }
            });
        }
        return doPublish(message);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public NetworkType getType() {
        return DefaultNetworkType.MQTT_CLIENT;
    }

    @Override
    public void shutdown() {
        loading = false;
        IllegalStateException shutdownError = new IllegalStateException("MQTT client has been shut down");
        List<PendingPublish> publishes = List.copyOf(pendingPublishes);
        pendingPublishes.clear();
        publishes.forEach(publish -> publish.fail(shutdownError));
        loadSuccessListener.clear();
        // Clean up topic tree to prevent memory leaks
        subscriber.clean();
        if (isAlive()) {
            try {
                client.disconnect();
            } catch (Exception ignore) {

            }
            client = null;
        }
    }

    private final class PendingPublish {
        private final MqttMessage message;
        private final MonoSink<Void> sink;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private PendingPublish(MqttMessage message, MonoSink<Void> sink) {
            this.message = message;
            this.sink = sink;
        }

        private void start() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            pendingPublishes.remove(this);
            doPublish(message).subscribe(sink::success, sink::error);
        }

        private void cancel() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            pendingPublishes.remove(this);
            ReferenceCountUtil.safeRelease(message.getPayload());
        }

        private void fail(Throwable error) {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            pendingPublishes.remove(this);
            ReferenceCountUtil.safeRelease(message.getPayload());
            sink.error(error);
        }
    }

    @Override
    public boolean isAlive() {
        return client != null && client.isConnected();
    }

    @Override
    public boolean isAutoReload() {
        return true;
    }

}
