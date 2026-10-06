package io.github.mkonline08.sidebar;

import java.util.Arrays;
import java.util.Locale;

final class PerformanceSamples {
    private final long[] samples=new long[200];
    private int size,index;
    void add(long nanos){samples[index]=Math.max(0,nanos);index=(index+1)%samples.length;size=Math.min(size+1,samples.length);}
    String report(int queued,double serverMs,double tps,Displays displays) {
        long[] values=Arrays.copyOf(samples,size);Arrays.sort(values);double mean=0;
        for(long value:values)mean+=value;mean=size==0?0:mean/size/1_000_000;
        double p95=size==0?0:values[Math.max(0,(int)Math.ceil(size*0.95)-1)]/1_000_000.0;
        double max=size==0?0:values[size-1]/1_000_000.0;
        if(displays==null)return String.format(Locale.ROOT,"MK market work: avg=%.3fms p95=%.3fms max=%.3fms server=%.3fms/tick tps=%.2f (last 200 commands, clicks, callbacks and maintenance calls; excludes database worker)",mean,p95,max,serverMs,Math.min(20,tps));
        return String.format(Locale.ROOT,"MK performance: avg=%.3fms p95=%.3fms max=%.3fms queued=%d server=%.3fms/tick tps=%.2f renders=%d tab-updates=%d sidebar-updates=%d",mean,p95,max,queued,serverMs,Math.min(20,tps),displays.renders(),displays.tabWrites(),displays.sidebarWrites());
    }
}
