package gp.saas.legacy.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DiYaAlarmRecordEntity {
    private String alarmId;
    private Integer alarmStatus;
    private Integer hiddenDanger;
    private Integer hiddenDangerStatus;
    private String confirmTime;
}
