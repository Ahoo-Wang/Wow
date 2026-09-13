/* Licensed under the Apache License, Version 2.0. */
package batchharness;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/** Frozen fixture reused only for backend construction, payloads, writes and native verification. */
public class OpenLoop {
    static final class Sample {long planned,submitted,terminal; volatile String result="not_sent";}
    static long awaitSlot(long planned,long nextAllowed) {
        long due=Math.max(planned,nextAllowed),delay;
        while((delay=due-System.nanoTime())>0){if(delay>1_000_000)LockSupport.parkNanos(delay-500_000);else Thread.onSpinWait();}
        long now=System.nanoTime();
        return now-planned>1_000_000L?0:now;
    }
    static long minimumGap(long period) {return period*9/10;}
    static int simulate(long period,long minimumGap) {
        long previous=0;int missed=0;
        for(int i=1;i<=20000;i++) {
            long planned=i*period,actual=Math.max(planned,previous+minimumGap)+100;
            if(actual-planned>1_000_000L){missed++;continue;}
            if(previous!=0 && actual-previous<minimumGap)throw new AssertionError("minimum gap");
            previous=actual;
        }
        return missed;
    }
    static void checkPacing() {
        if(simulate(200_000L,200_000L)==0)throw new AssertionError("drift reproduction absent");
        if(simulate(200_000L,minimumGap(200_000L))!=0)throw new AssertionError("phase correction failed");
        long period=200_000L,start=System.nanoTime()+10_000_000L,last=0;int sent=0,missed=0;
        for(int i=0;i<5000;i++) {
            long now=awaitSlot(start+i*period,last==0?start:last+minimumGap(period));
            if(now==0){missed++;continue;}
            if(last!=0 && now-last<minimumGap(period))throw new AssertionError("catch-up burst");
            last=now;sent++;
        }
        if(sent+missed!=5000 || sent==0)throw new AssertionError("slot accounting");
        System.out.println("pacing self-check: sent="+sent+" missed="+missed+" / 5000, minimum spacing verified");
    }
    static void interval(BatchLatency f,int rate,int seconds,Path output) throws Throwable {
        int count=Math.multiplyExact(rate,seconds); long period=1_000_000_000L/rate;
        Object[] inputs=new Object[count]; Sample[] samples=new Sample[count];
        for(int i=0;i<count;i++){inputs[i]=f.item(f.sequence.getAndIncrement());samples[i]=new Sample();}
        CountDownLatch done=new CountDownLatch(count);
        long start=System.nanoTime()+10_000_000L, nextAllowed=start;
        for(int i=0;i<count;i++) {
            Sample s=samples[i]; s.planned=start+i*period;
            // Phase locked: bounded <=11.1% local rate correction; no zero-gap catch-up bursts.
            long now=awaitSlot(s.planned,nextAllowed);
            if(now==0){s.terminal=System.nanoTime();done.countDown();continue;}
            s.submitted=now;nextAllowed=now+minimumGap(period);
            try {
                f.write(inputs[i]).timeout(BatchLatency.TIMEOUT).subscribe(v->{},e->{s.terminal=System.nanoTime();s.result=e.getClass().getName();done.countDown();},()->{s.terminal=System.nanoTime();s.result="success";done.countDown();});
            } catch(Throwable e){s.terminal=System.nanoTime();s.result=e.getClass().getName();done.countDown();}
        }
        if(!done.await(20,TimeUnit.SECONDS))throw new AssertionError("undrained notifications");
        long success=0;for(Sample s:samples)if(s.result.equals("success"))success++;
        f.confirmed.addAndGet(success);f.verify();
        try(var out=new BufferedWriter(new OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(output)),java.nio.charset.StandardCharsets.UTF_8))) {
            out.write("planned_ns,submitted_ns,terminal_ns,result\n");
            for(Sample s:samples)out.write((s.planned-start)+","+(s.submitted==0?0:s.submitted-start)+","+(s.terminal-start)+","+s.result+"\n");
        }
    }
    public static void main(String[] args)throws Throwable {
        if(args.length==1 && args[0].equals("--self-test")){checkPacing();return;}
        int rate=Integer.parseInt(args[0]),seconds=Integer.parseInt(args[1]);Path prefix=Path.of(args[2]);
        try(var f=new BatchLatency("mongo-event","multi",4)) {
            for(int i=0;i<3;i++){f.reset();interval(f,rate,seconds,Path.of(prefix+"-warm-"+i+".csv.gz"));}
            for(int i=0;i<3;i++){f.reset();interval(f,rate,seconds,Path.of(prefix+"-measure-"+i+".csv.gz"));}
        }
    }
}
