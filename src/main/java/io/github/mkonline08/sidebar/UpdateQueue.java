package io.github.mkonline08.sidebar;

import java.util.*;

/** Deduplicated FIFO: a crowded server slows refreshes instead of creating an unbounded backlog. */
final class UpdateQueue {
    private final ArrayDeque<UUID> queue=new ArrayDeque<>();
    private final Set<UUID> pending=new HashSet<>();
    void offer(UUID id){if(pending.add(id))queue.addLast(id);}
    void refresh(Collection<UUID> online){Set<UUID> ids=new HashSet<>(online);queue.removeIf(id->!ids.contains(id));pending.retainAll(ids);for(UUID id:online)offer(id);}
    UUID poll(){UUID id=queue.pollFirst();if(id!=null)pending.remove(id);return id;}
    int size(){return queue.size();}
}
