package org.jetlinks.community.saas.propertycatalog.service;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.PrepareSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.term.AbstractTermFragmentBuilder;
import org.springframework.stereotype.Component;

/** Portable, parameterized, case-insensitive literal substring search. */
@Component
public class CatalogKeywordTerm extends AbstractTermFragmentBuilder {
    public static final String TYPE = "catalog-contains";
    public CatalogKeywordTerm() { super(TYPE, "采集项库关键字"); }
    @Override
    public SqlFragments createFragments(String columnFullName, RDBColumnMetadata column, Term term) {
        String value = String.valueOf(term.getValue()).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return PrepareSqlFragments.of().addSql("LOWER(", columnFullName, ") LIKE LOWER(?) ESCAPE '!'").addParameter("%" + value + "%");
    }
}
