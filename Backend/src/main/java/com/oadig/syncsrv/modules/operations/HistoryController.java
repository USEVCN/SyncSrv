package com.oadig.syncsrv.modules.operations;
import com.oadig.syncsrv.platform.persistence.Store;
import com.oadig.syncsrv.shared.Models.*;
import com.oadig.syncsrv.shared.web.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/history")
public final class HistoryController {
  private final Store store;
  public HistoryController(Store store){this.store=store;}
  @GetMapping("/nearest") public ApiResponse<?> nearest(@RequestParam String jobId,@RequestParam String at)throws Exception{return ApiResponse.ok(store.nearestSnapshot(jobId,at));}
  @GetMapping("/runs") public ApiResponse<?> runs(@RequestParam(defaultValue="100")int limit,@RequestParam(defaultValue="0")int offset)throws Exception{return ApiResponse.ok(store.history("run",limit,offset,Run.class));}
  @GetMapping("/snapshots") public ApiResponse<?> snapshots(@RequestParam(defaultValue="100")int limit,@RequestParam(defaultValue="0")int offset)throws Exception{return ApiResponse.ok(store.history("snapshot-summary",limit,offset,SnapshotSummary.class));}
}
