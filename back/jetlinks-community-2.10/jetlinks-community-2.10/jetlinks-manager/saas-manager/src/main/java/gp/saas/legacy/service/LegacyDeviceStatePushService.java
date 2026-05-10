package gp.saas.legacy.service;

import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.entity.DeviceStateInfo;
import org.jetlinks.community.device.enums.DeviceState;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.gateway.annotation.Subscribe;
import org.jetlinks.core.message.DeviceMessage;
import org.jetlinks.core.message.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LegacyDeviceStatePushService implements CommandLineRunner {

    private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofMinutes(15);

    private final LocalDeviceInstanceService deviceService;
    private final LegacyMessagePublishService messagePublishService;

    @Value("${gplink.device-state-push.enabled:true}")
    private boolean enabled = true;

    @Value("${gplink.device-state-push.refresh-interval:PT15M}")
    private Duration refreshInterval;

    @Value("${gplink.device-state-push.initial-delay:PT15M}")
    private Duration initialDelay;

    @Value("${gplink.device-state-push.batch-size:200}")
    private int batchSize;

    @Value("${gplink.device-state-push.concurrency:8}")
    private int concurrency;

    private Disposable refreshDisposable;

    public LegacyDeviceStatePushService(LocalDeviceInstanceService deviceService,
                                        LegacyMessagePublishService messagePublishService) {
        this.deviceService = deviceService;
        this.messagePublishService = messagePublishService;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }

        Duration interval = positive(refreshInterval, DEFAULT_REFRESH_INTERVAL);
        Duration delay = initialDelay == null || initialDelay.isNegative()
            ? interval
            : initialDelay;

        refreshDisposable = Flux
            .interval(delay, interval)
            .concatMap(tick -> refreshAllDeviceStates()
                .doOnNext(count -> {
                    if (count > 0) {
                        log.info("legacy device state refresh published {} state changes", count);
                    }
                })
                .onErrorResume(error -> {
                    log.warn("legacy device state refresh failed", error);
                    return Mono.empty();
                }))
            .subscribe();
    }

    @PreDestroy
    public void shutdown() {
        if (refreshDisposable != null && !refreshDisposable.isDisposed()) {
            refreshDisposable.dispose();
        }
    }

    @Subscribe(topics = {
        "/device/*/*/online",
        "/device/*/*/offline"
    })
    public Mono<Void> onlineOfflineMessageHandler(DeviceMessage message) {
        if (!enabled) {
            return Mono.empty();
        }

        DeviceState state = resolveMessageState(message);
        if (state == null || !StringUtils.hasText(message.getDeviceId())) {
            return Mono.empty();
        }

        return resolvePushableDevice(message.getDeviceId())
            .flatMap(device -> publishDeviceState(device, state, messageTime(message)))
            .then();
    }

    public Mono<Integer> refreshAllDeviceStates() {
        if (!enabled) {
            return Mono.just(0);
        }

        return deviceService
            .createQuery()
            .where()
            .not(DeviceInstanceEntity::getState, DeviceState.notActive)
            .fetch()
            .filter(this::isPushableDevice)
            .buffer(Math.max(1, batchSize))
            .concatMap(this::refreshDeviceStateBatch)
            .reduce(0, Integer::sum)
            .defaultIfEmpty(0);
    }

    private Mono<Integer> refreshDeviceStateBatch(List<DeviceInstanceEntity> devices) {
        if (devices.isEmpty()) {
            return Mono.just(0);
        }

        Map<String, DeviceInstanceEntity> previousDevices = devices
            .stream()
            .collect(Collectors.toMap(DeviceInstanceEntity::getId, Function.identity(), (left, right) -> left));
        List<String> deviceIds = devices
            .stream()
            .map(DeviceInstanceEntity::getId)
            .filter(StringUtils::hasText)
            .collect(Collectors.toList());

        return deviceService
            .syncStateBatch(Flux.just(deviceIds), true)
            .flatMapIterable(Function.identity())
            .flatMap(info -> publishIfChanged(previousDevices.get(info.getDeviceId()), info),
                     Math.max(1, concurrency))
            .reduce(0, Integer::sum)
            .defaultIfEmpty(0);
    }

    private Mono<Integer> publishIfChanged(DeviceInstanceEntity previousDevice, DeviceStateInfo currentInfo) {
        if (previousDevice == null || currentInfo == null) {
            return Mono.just(0);
        }
        DeviceState previous = previousDevice.getState();
        DeviceState current = currentInfo.getState();
        if (!isOnlineOrOffline(previous) || !isOnlineOrOffline(current) || previous == current) {
            return Mono.just(0);
        }
        return publishDeviceState(previousDevice, current, System.currentTimeMillis())
            .map(published -> Boolean.TRUE.equals(published) ? 1 : 0);
    }

    private Mono<DeviceInstanceEntity> resolvePushableDevice(String deviceId) {
        return deviceService
            .findById(deviceId)
            .filter(this::isPushableDevice);
    }

    private boolean isPushableDevice(DeviceInstanceEntity device) {
        return device != null
            && StringUtils.hasText(device.getId())
            && StringUtils.hasText(device.getProductId())
            && device.getState() != DeviceState.notActive;
    }

    private Mono<Boolean> publishDeviceState(DeviceInstanceEntity device, DeviceState state, long timestamp) {
        String legacyState = toLegacyState(state);
        if (!StringUtils.hasText(legacyState)) {
            return Mono.just(false);
        }

        return messagePublishService
            .standardMqttDeviceStatePublisher(device.getProductId(), device.getId(), legacyState, timestamp)
            .onErrorResume(error -> {
                log.warn("legacy device state mqtt publish failed: productId={}, deviceId={}, state={}",
                         device.getProductId(), device.getId(), legacyState, error);
                return Mono.just(false);
            });
    }

    private DeviceState resolveMessageState(DeviceMessage message) {
        if (message.getMessageType() == MessageType.ONLINE) {
            return DeviceState.online;
        }
        if (message.getMessageType() == MessageType.OFFLINE) {
            return DeviceState.offline;
        }
        return null;
    }

    private static long messageTime(DeviceMessage message) {
        long timestamp = message.getTimestamp();
        return timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    private static String toLegacyState(DeviceState state) {
        if (state == DeviceState.online) {
            return "Online";
        }
        if (state == DeviceState.offline) {
            return "Offline";
        }
        return null;
    }

    private static boolean isOnlineOrOffline(DeviceState state) {
        return state == DeviceState.online || state == DeviceState.offline;
    }

    private static Duration positive(Duration value, Duration fallback) {
        if (value == null || value.isZero() || value.isNegative()) {
            return fallback;
        }
        return value;
    }
}
