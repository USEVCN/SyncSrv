package com.oadig.syncsrv.modules.transfer;

import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.Run;
import java.util.*;
import java.util.function.LongSupplier;

/** Logical work is counted once; transfer and verification are independent sampled rates. */
public final class RunProgress {
  public static final int PAGE_SIZE = 100;
  public record Summary(boolean planComplete, long processedBytes, long totalBytes,
      int processedFiles, int totalFiles, int entries, long elapsedMillis,
      Long estimatedTotalMillis, double transferBytesPerSecond, double verificationBytesPerSecond,
      String sampledAt, int activeFiles, int peakActiveFiles, int concurrencyLimit,
      int activeChecks, int peakChecks) {}
  public record FileRow(int id, String path, String kind, String status, long size,
      long processedBytes, String phase, long phaseBytes, long phaseTotal, String error) {}
  public record Page(int number, List<FileRow> entries) {}
  public record Pending(int number, long revision, Page page) {}
  private static final class Entry {
    int id; String path, kind, status="QUEUED", phase="", error="";
    long size, processed, revision, phaseBytes, phaseTotal, transferOffset; boolean primaryTransfer;
    boolean file() { return !kind.equals("MKDIR") && !kind.equals("RMDIR"); }
    FileRow row(Run run, boolean active) {
      return new FileRow(id,path,kind,status,size,processed,phase, phaseBytes,phaseTotal,error);
    }
  }
  private record Sample(long at, long transferred, long verified) {}
  private final List<Entry> entries = new ArrayList<>();
  private final Map<String, ArrayDeque<Entry>> waiting = new HashMap<>();
  private final Map<Integer, Long> dirty = new TreeMap<>();
  private final ArrayDeque<Sample> samples = new ArrayDeque<>();
  private final LongSupplier clock;
  private long started=-1, ended=-1, revision, totalBytes, completedBytes, partialBytes, transferred;
  private int files, completedFiles, peakActiveFiles, concurrencyLimit, activeChecks, peakChecks;
  public synchronized void checkStarted() { peakChecks = Math.max(peakChecks, ++activeChecks); }
  public synchronized void checkFinished() { activeChecks--; }
  public synchronized void concurrency(int limit) { concurrencyLimit = limit; }
  private boolean planned;
  private final ThreadLocal<Entry> local = new ThreadLocal<>();
  private final Map<Integer,Entry> active = new HashMap<>();
  private long workStarted=-1, lastCompletion=-1;
  private Entry current(){Entry e=local.get();return e!=null && e.status.equals("RUNNING") ? e : null;}
  public synchronized boolean select(String path){for(Entry e:active.values())if(e.path.equals(path)){local.set(e);return true;}local.remove();return false;}
  public synchronized void phaseProgress(long bytes,long total){Entry e=current();if(e!=null){e.phaseBytes=bytes;e.phaseTotal=total;changed(e);}}
  public synchronized void verified(long count){Entry e=current();if(e!=null && e.phaseTotal>0){e.phaseBytes+=Math.max(0,count);changed(e);}}


  public RunProgress() { this(System::nanoTime); }
  public RunProgress(LongSupplier clock) { this.clock=clock; }
  public synchronized void start() { started=clock.getAsLong(); samples.add(new Sample(started,0,0)); }
  public synchronized void plan(String path, String kind, long size) {
    Entry e=new Entry(); e.id=entries.size(); e.path=path; e.kind=kind;
    e.size=Math.max(0,size); entries.add(e);
    waiting.computeIfAbsent(kind+"\n"+path,k->new ArrayDeque<>()).add(e);
    if(e.file()) {files++; totalBytes+=e.size;}
    changed(e);
  }
  public synchronized void planned() { planned=true; }
  public synchronized void begin(String path, String kind, long size) {
    var queue=waiting.get(kind+"\n"+path);
    if(queue==null || queue.isEmpty()) {plan(path,kind,size);queue=waiting.get(kind+"\n"+path);}
    Entry current=queue.removeFirst();local.set(current);active.put(current.id,current); current.status="RUNNING"; changed(current);
    peakActiveFiles=Math.max(peakActiveFiles,(int)active.values().stream().filter(Entry::file).count());
    if(current.file() && workStarted<0) workStarted=clock.getAsLong();
  }
  public synchronized void stage(String phase) {
    Entry current=current();
    if(current!=null) {current.phase=phase;changed(current);}
  }
  public synchronized void beginTransfer(long offset, String phase) {
    Entry current=current();if(current==null)return;
    current.primaryTransfer=current.kind.equals("DELETE") || !phase.equals("ARCHIVING");
    current.transferOffset=offset;
    if(current.primaryTransfer) {current.processed=Math.min(current.size,offset);changed(current);}
  }
  public synchronized void observeTransferred(long count) { transferred+=Math.max(0,count); }
  public synchronized void transferred(int count) {
    if(count<=0)return;
    transferred+=count;Entry current=current();
    if(current!=null){current.transferOffset+=count;if(current.primaryTransfer){current.processed=Math.min(current.size,current.transferOffset);changed(current);}}
  }
  public synchronized void finishFile(String status, String error) {
    Entry current=current();if(current==null)return;
    current.status=status; current.error=error==null ? "" : error;
    if(status.equals("COMPLETED")) {
      current.processed=current.size;
      if(current.file()) {completedFiles++;completedBytes+=current.size;lastCompletion=clock.getAsLong();}
    } else if(status.equals("SKIPPED") && current.file()) {files--;totalBytes-=current.size;}
    if(current.file() && Set.of("FAILED","PAUSED","INTERRUPTED").contains(status)) partialBytes+=current.processed;
    changed(current);active.remove(current.id);local.remove();
  }
  public synchronized void finish(Run run) {
    for(Entry e:List.copyOf(active.values())){local.set(e);finishFile(run.status,run.error);}ended=clock.getAsLong();
  }
  private void changed(Entry e) { e.revision=++revision;dirty.put(e.id/PAGE_SIZE,revision); }
  public synchronized Summary snapshot(Run run) {
    long now=clock.getAsLong();
    boolean active=ended<0 && run.status.equals("RUNNING");
    Sample sample=new Sample(now,transferred,run.verifiedBytes);
    if(samples.isEmpty() || now-samples.getLast().at>=500_000_000L) samples.add(sample);
    while(samples.size()>2 && now-samples.getFirst().at>5_000_000_000L) samples.removeFirst();
    Sample first=samples.getFirst();double seconds=(now-first.at)/1e9;
    double speed=active && seconds>0 ? Math.max(0,(transferred-first.transferred)/seconds) : 0;
    double verify=active && seconds>0 ? Math.max(0,(run.verifiedBytes-first.verified)/seconds) : 0;
    long elapsed=started<0 ? 0 : Math.max(0,(ended<0 ? now : ended)-started)/1_000_000;
    long processed=completedBytes+partialBytes+this.active.values().stream().filter(Entry::file).mapToLong(e->e.processed).sum();
    Long estimate=null;
    // A completed file includes all its archive, verification and commit work. Do not
    // extrapolate the one-time initial scan from partially copied bytes of the first file.
    double fraction=totalBytes>0 ? (double)completedBytes/totalBytes : files>0 ? (double)completedFiles/files : 0;
    if(run.status.equals("COMPLETED")) estimate=elapsed;
    else if(active && planned && elapsed>=3000 && completedFiles>0 && fraction>0 && fraction<1) {
      double workMillis=Math.max(0,lastCompletion-workStarted)/1e6;
      double remaining=workMillis*(1/fraction-1)-Math.max(0,now-lastCompletion)/1e6;
      if(remaining>0) estimate=(long)Math.min(Long.MAX_VALUE,elapsed+remaining);
    }
    return new Summary(planned,Math.min(totalBytes,processed),totalBytes,completedFiles,files,
        entries.size(),elapsed,estimate,speed,verify,Ids.now(),
        (int)this.active.values().stream().filter(Entry::file).count(),peakActiveFiles,concurrencyLimit,activeChecks,peakChecks);
  }
  public synchronized Page page(int number, Run run) {
    int from=Math.min(entries.size(),number*PAGE_SIZE),to=Math.min(entries.size(),from+PAGE_SIZE);
    return new Page(number,entries.subList(from,to).stream().map(e->e.row(run,active.containsKey(e.id))).toList());
  }
  public synchronized List<Pending> pending(Run run) {
    for(Entry current:active.values()) changed(current); // Include current verification progress in the periodic checkpoint.
    return dirty.entrySet().stream().map(e->new Pending(e.getKey(),e.getValue(),page(e.getKey(),run))).toList();
  }
  public synchronized void saved(List<Pending> pages) {
    pages.forEach(p->dirty.remove(p.number,p.revision));
  }
  public static String pageId(String runId,int page) {
    String key=runId+":"+page;
    return key.length()<=64 ? key : Ids.textHash(key);
  }
}
