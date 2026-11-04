package com.oadig.syncsrv.modules.jobs;

import static com.oadig.syncsrv.platform.persistence.Store.Change.put;

import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.Run;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Human investigation stays separate from the original execution and its signed report. */
@Service
public final class RunDispositions {
  public record Input(int revision, String reason, String plan, String method) {}

  public record Entry(
      String runId,
      int revision,
      String reason,
      String plan,
      String method,
      String actor,
      String savedAt) {}

  public record View(String originalError, Entry latest, List<Entry> history, int nextBefore) {}

  private final Store store;

  public RunDispositions(Store store) {
    this.store = store;
  }

  public synchronized View read(String runId, int before) throws Exception {
    Run run = failedRun(runId);
    if (before < 0) throw new IllegalArgumentException("历史版本参数不正确");
    Entry latest = store.get("run-disposition", runId, Entry.class);
    int end =
        latest == null
            ? 0
            : before == 0 ? latest.revision() : Math.min(latest.revision(), before - 1);
    int start = Math.max(1, end - 19);
    List<String> keys = new ArrayList<>();
    for (int version = end; version >= start; version--) keys.add(key(runId, version));
    Map<String, Entry> saved = store.getMany("run-disposition-history", keys, Entry.class);
    List<Entry> history = new ArrayList<>();
    for (String key : keys) {
      Entry entry = saved.get(key);
      if (entry == null) throw new IllegalStateException("处置历史不完整，请检查审计记录");
      history.add(entry);
    }
    return new View(run.error, latest, history, start > 1 ? start : 0);
  }

  public synchronized Entry save(String runId, Input input, String actor) throws Exception {
    Run run = failedRun(runId);
    if (input == null || input.revision() < 0) throw new IllegalArgumentException("处置内容不正确");
    String reason = field(input.reason(), "失败原因");
    String plan = field(input.plan(), "处置方案");
    String method = field(input.method(), "处置方法");
    if (reason.isEmpty() && plan.isEmpty() && method.isEmpty())
      throw new IllegalArgumentException("请至少填写失败原因、处置方案或处置方法中的一项");
    Entry previous = store.get("run-disposition", runId, Entry.class);
    int version = previous == null ? 0 : previous.revision();
    if (version != input.revision())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "处置记录已被更新，请重新加载后再保存；当前输入已保留");
    if (previous != null
        && previous.reason().equals(reason)
        && previous.plan().equals(plan)
        && previous.method().equals(method)) return previous;
    Entry next =
        new Entry(runId, Math.addExact(version, 1), reason, plan, method, actor, Ids.now());
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("originalError", run.error);
    detail.put("before", previous);
    detail.put("after", next);
    store.commit(
        actor,
        "run.disposition_saved",
        runId,
        detail,
        put("run-disposition", runId, next),
        put("run-disposition-history", key(runId, next.revision()), next));
    return next;
  }

  private Run failedRun(String id) throws Exception {
    Run run = store.get("run", id, Run.class);
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "执行记录不存在");
    if (!"FAILED".equals(run.status)) throw new IllegalArgumentException("仅失败的执行记录可以填写处置内容");
    return run;
  }

  private static String field(String value, String label) {
    String text = value == null ? "" : value.strip();
    if (text.length() > 4000) throw new IllegalArgumentException(label + "最多填写 4000 字");
    return text;
  }

  private static String key(String runId, int version) {
    return runId + ":" + version;
  }
}
