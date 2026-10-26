package org.jetlinks.community.device.service.data;

import org.jetlinks.community.Interval;
import org.jetlinks.community.things.ThingsDataRepository;
import org.jetlinks.community.things.data.PropertyAggregation;
import org.jetlinks.community.things.data.operations.QueryOperations;
import org.jetlinks.community.things.data.operations.TemplateOperations;
import org.jetlinks.community.things.data.operations.ThingOperations;
import org.jetlinks.community.timeseries.query.Aggregation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThingsBridgingAggregationLimitTest {

    @ParameterizedTest
    @CsvSource({"false,5m", "true,5m", "false,1w", "true,1w", "false,1q", "true,1q"})
    void preservesExplicitLimitIntervalAndBounds(boolean byProduct, String interval) {
        ThingsDataRepository repository = mock(ThingsDataRepository.class);
        QueryOperations query = mock(QueryOperations.class);
        if (byProduct) {
            TemplateOperations operations = mock(TemplateOperations.class);
            when(repository.opsForTemplate(anyString(), anyString())).thenReturn(Mono.just(operations));
            when(operations.forQuery()).thenReturn(query);
        } else {
            ThingOperations operations = mock(ThingOperations.class);
            when(repository.opsForThing(anyString(), anyString())).thenReturn(Mono.just(operations));
            when(operations.forQuery()).thenReturn(query);
        }
        when(query.aggregationProperties(any(), any(PropertyAggregation[].class))).thenReturn(Flux.empty());

        DeviceDataService.AggregationRequest request = new DeviceDataService.AggregationRequest();
        long start = LocalDate.of(2026, 10, 23).atStartOfDay(ZoneId.of("Asia/Shanghai"))
                              .toInstant().toEpochMilli();
        request.setFrom(new Date(start));
        request.setTo(new Date(start + 86_400_000L - 1));
        request.setInterval(Interval.of(interval));
        request.setFormat("yyyy-MM-dd HH:mm:ss");
        request.setLimit(5000);
        DeviceDataService.DevicePropertyAggregation column = new DeviceDataService.DevicePropertyAggregation();
        column.setProperty("P");
        column.setAlias("P");
        column.setAgg(Aggregation.FIRST);
        ThingsBridgingDeviceDataService service = new ThingsBridgingDeviceDataService(repository);

        StepVerifier.create(byProduct
            ? service.aggregationPropertiesByProduct("product-1", request, column)
            : service.aggregationPropertiesByDevice("device-1", request, column))
                    .verifyComplete();

        ArgumentCaptor<org.jetlinks.community.things.data.AggregationRequest> captured =
            ArgumentCaptor.forClass(org.jetlinks.community.things.data.AggregationRequest.class);
        verify(query).aggregationProperties(captured.capture(), any(PropertyAggregation[].class));
        org.jetlinks.community.things.data.AggregationRequest forwarded = captured.getValue();
        assertThat(forwarded.getExplicitLimit()).isEqualTo(5000);
        assertThat(forwarded.copy().getExplicitLimit()).isEqualTo(5000);
        assertThat(forwarded.getFrom()).isEqualTo(request.getFrom());
        assertThat(forwarded.getTo()).isEqualTo(request.getTo());
        assertThat(forwarded.getInterval()).isEqualTo(request.getInterval());
    }
}
