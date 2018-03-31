package org.act.temporalProperty.index;

import com.google.common.collect.PeekingIterator;
import org.act.temporalProperty.index.rtree.IndexEntry;
import org.act.temporalProperty.index.rtree.IndexEntryOperator;
import org.act.temporalProperty.util.Slice;

import java.util.*;

/**
 * Created by song on 2018-01-21.
 */
public class IndexBuilderCallback {
    private final IndexEntryOperator op;
    private List<TimePointEntry> data = new ArrayList<>();
    private Set<Integer> proIdSet;
    private List<Integer> proIdList;

    public IndexBuilderCallback(List<Integer> proIds, IndexEntryOperator op){
        this.proIdList = proIds;
        this.proIdSet = new HashSet<>(proIds);
        this.op = op;
    }

    public void onCall(int propertyId, long entityId, int startTime, Slice value) {
        if(!proIdSet.contains(propertyId)) throw new RuntimeException("SNH: pro not in given list");
        data.add(new TimePointEntry(propertyId, entityId, startTime, value));
    }

    public PeekingIterator<IndexEntry> getIterator(int startTime, int endTime){
        return new IndexPoint2IntervalIterator(proIdList, data, startTime, endTime, op);
    }


}
