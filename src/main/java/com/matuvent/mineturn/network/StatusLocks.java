package com.matuvent.mineturn.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client leases are renewed by server status snapshots, independently of which GUI is open. */
public final class StatusLocks {
    private final Map<UUID,Long> leases=new HashMap<>();
    public void update(UUID entity,boolean locked,long now){if(locked)leases.put(entity,now+5_000_000_000L);else leases.remove(entity);}
    public boolean locked(UUID entity,long now){Long until=leases.get(entity);if(until==null)return false;if(now>=until){leases.remove(entity);return false;}return true;}
    public void clear(){leases.clear();}
}
