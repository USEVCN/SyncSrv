package com.oadig.syncsrv.modules.settings;

import com.oadig.syncsrv.platform.persistence.Store;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class UiSettings {
  public record Value(String systemName, String description, Map<String,Integer> typography) {}
  private static final String[] KEYS = {"caption", "compact", "list", "body", "section", "page", "display"};
  private static final int[] DEFAULTS = {11,12,13,14,16,22,28}, MIN = {10,11,12,12,14,18,22}, MAX = {18,20,22,24,28,36,48};
  private final Store store;
  public UiSettings(Store store) { this.store = store; }

  public Value get() throws Exception {
    Value value = store.get("ui-settings", "global", Value.class);
    return value == null ? defaults() : value;
  }

  public synchronized Value save(Value value, String actor) throws Exception {
    if (value == null || value.systemName() == null || value.systemName().isBlank()
        || value.systemName().strip().length() > 60 || value.systemName().contains("\0")
        || value.description() == null || value.description().length() > 240 || value.description().contains("\0"))
      throw new IllegalArgumentException("系统名称限 1–60 字，说明限 240 字");
    Map<String,Integer> scale = value.typography();
    if (scale == null || !scale.keySet().equals(Set.of(KEYS))) throw new IllegalArgumentException("请提供完整的七级字号");
    int prior = 0;
    for (int i=0; i<KEYS.length; i++) {
      Integer size = scale.get(KEYS[i]);
      if (size == null || size < MIN[i] || size > MAX[i] || size < prior)
        throw new IllegalArgumentException("字号必须在各级范围内，且后一级不能小于前一级");
      prior = size;
    }
    Value next = new Value(value.systemName().strip(), value.description().strip(), Map.copyOf(scale));
    store.commit(actor, "ui-settings.saved", "global", next, Store.Change.put("ui-settings", "global", next));
    return next;
  }

  public static Value defaults() {
    Map<String,Integer> scale = new LinkedHashMap<>();
    for (int i=0; i<KEYS.length; i++) scale.put(KEYS[i], DEFAULTS[i]);
    return new Value("SyncSrv", "备份、归档与恢复", scale);
  }
}
