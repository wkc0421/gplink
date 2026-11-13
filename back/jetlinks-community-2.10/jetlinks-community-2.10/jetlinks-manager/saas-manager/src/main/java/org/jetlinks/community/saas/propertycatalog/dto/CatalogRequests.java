package org.jetlinks.community.saas.propertycatalog.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CatalogRequests {
    private CatalogRequests() { }
    @Getter @Setter public static class ItemQuery {
        private String libraryId;
        private String categoryId;
        private boolean includeChildren = true;
        private String keyword;
        private Boolean enabled;
        private int pageIndex;
        private int pageSize = 20;
    }
    @Getter @Setter public static class BatchCategory {
        private String libraryId;
        private List<String> itemIds;
        private String categoryId;
    }
    @Getter @Setter public static class Adopt {
        private List<String> itemIds;
        private String categoryId;
    }
    @Getter @Setter public static class ImportRow {
        private Integer rowNumber;
        private String code;
        private String name;
        private String categoryPath;
        private String remark;
        private Integer sortIndex;
        private Map<String, Object> source;
        private List<String> errors = new ArrayList<>();
    }
    @Getter @Setter public static class Confirm {
        private List<ImportRow> rows;
    }
    @Getter @Setter public static class Preview {
        private List<ImportRow> rows = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }
    public record Conflict(String code, String name, String existingName) { }
    @Getter @Setter public static class ImportResult {
        private int created;
        private int skipped;
        private List<Conflict> conflicts = new ArrayList<>();
    }
}
