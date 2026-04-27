package gp.saas.legacy.dto;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.core.param.Term;
import org.jetlinks.community.rule.engine.commons.ShakeLimit;

import java.util.List;

@Getter
@Setter
public class AlarmRuleEntity {
    private String name;
    private String productId;
    private String deviceIds;
    private List<Term> termList;
    private Term.Type type;
    private ShakeLimit shakeLimit;
}
