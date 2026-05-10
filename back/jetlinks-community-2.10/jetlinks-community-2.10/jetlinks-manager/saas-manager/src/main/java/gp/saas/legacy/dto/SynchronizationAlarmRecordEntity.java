package gp.saas.legacy.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SynchronizationAlarmRecordEntity {
    private Long id;
    private LocalDateTime createTime;
    private String sourceId;
    private String sourceName;
    private String sourceType;
    private Integer level;
    private Long firstAlarmTime;
    private LocalDateTime confirmTime;
    private LocalDateTime handleTime;
    private Boolean isConfirmed;
    private Boolean isAlarming;
    private Boolean isDanger;
    private String property;
    private String propertyValue;
    private String alarmName;
    private String alarmId;
    private String localAlarmId;
    private String alarmConfigId;
    private String deviceId;
    private String deviceName;
    private String description;
    private Integer dangerFlag;
    private String dangerType;
}
