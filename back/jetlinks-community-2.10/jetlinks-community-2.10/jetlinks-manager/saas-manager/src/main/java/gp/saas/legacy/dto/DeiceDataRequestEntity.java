package gp.saas.legacy.dto;

import lombok.Getter;
import lombok.Setter;
import org.jetlinks.community.device.service.data.DeviceDataService;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class DeiceDataRequestEntity {

    private String name;

    private List<String> devices = new ArrayList<>();

    private List<String> properties = new ArrayList<>();

    private AggregationRequestBody aggregationRequest;

    @Getter
    @Setter
    public static class AggregationRequestBody {
        private DeviceDataService.AggregationRequest query;

        private List<DeviceDataService.DevicePropertyAggregation> columns = new ArrayList<>();
    }
}
