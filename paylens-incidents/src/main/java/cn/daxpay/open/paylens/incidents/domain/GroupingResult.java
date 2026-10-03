package cn.daxpay.open.paylens.incidents.domain;

import java.util.List;

/** Result of one grouping run. */
public record GroupingResult(List<EventGroup> groups, String algorithmVersion) {

    public GroupingResult {
        groups = List.copyOf(groups);
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("algorithmVersion must not be blank");
        }
        if (groups.stream().anyMatch(group -> !algorithmVersion.equals(group.algorithmVersion()))) {
            throw new IllegalArgumentException("all event groups must use the result algorithmVersion");
        }
    }
}
