package cn.daxpay.open.paylens.incidents.domain;

import java.util.Collection;

/** Groups observations into candidate incidents. */
public interface Grouper {

    GroupingResult group(Collection<Observation> observations, GroupingPolicy policy);
}
