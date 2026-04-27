package gp.saas.legacy.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SynchronizationAlarmRecordEntity {
    private Long id;
    private LocalDateTime createTime;
    private Integer level;
    private LocalDateTime confirmTime;
    private LocalDateTime handleTime;
    private Boolean isConfirmed;
    private Boolean isAlarming;
    private Boolean isDanger;
    private String localAlarmId;
    private String deviceId;
    private String deviceName;
    private Integer dangerFlag;
}
