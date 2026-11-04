package com.oadig.syncsrv.platform.storage;

import com.hierynomus.mssmb2.SMBApiException;
import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import java.net.*;
import java.util.*;

/** Safe diagnostic text for clients; the original exception remains available in server logs. */
public final class SmbFailure extends IOException {
  public enum Stage {
    NEGOTIATE("协商协议"), AUTHENTICATE("身份验证"), SHARE("连接共享"), DIRECTORY("读取目录");

    final String label;

    Stage(String label) {
      this.label = label;
    }
  }

  private final String code;
  private final Stage stage;

  private SmbFailure(String code, Stage stage, String message, Exception cause) {
    super(message, cause);
    this.code = code;
    this.stage = stage;
  }

  public String code() {
    return code;
  }

  public Map<String, String> details() {
    return Map.of("reason", "SmbFailure", "code", code, "stage", stage.name(), "message", getMessage());
  }

  public static SmbFailure from(Endpoint endpoint, Stage stage, Exception cause) {
    if (cause instanceof SmbFailure failure) return failure;
    List<Throwable> chain = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = cause; current != null && seen.add(current); current = current.getCause())
      chain.add(current);
    String code = "SMB_OPERATION_FAILED";
    String hint = "请检查共享路径、账户权限和 NAS 服务日志。";
    for (Throwable current : chain) {
      if (!(current instanceof SMBApiException api)) continue;
      switch (api.getStatus()) {
        case STATUS_LOGON_FAILURE, STATUS_ACCOUNT_DISABLED, STATUS_PASSWORD_EXPIRED,
            STATUS_LOGON_TYPE_NOT_GRANTED -> {
          code = "SMB_AUTH_FAILED";
          hint = "NAS 拒绝登录，请核对 SMB 账号、密码、域及账号状态。";
        }
        case STATUS_ACCESS_DENIED -> {
          code = "SMB_ACCESS_DENIED";
          hint = "NAS 拒绝访问，请检查该账号的 SMB 及共享目录权限。";
        }
        case STATUS_BAD_NETWORK_NAME -> {
          code = "SMB_SHARE_NOT_FOUND";
          hint = "共享名称不存在，请填写“共享名/子目录”，不要填写 NAS 的系统目录路径。";
        }
        case STATUS_OBJECT_NAME_NOT_FOUND, STATUS_OBJECT_PATH_NOT_FOUND, STATUS_NO_SUCH_FILE -> {
          code = "SMB_PATH_NOT_FOUND";
          hint = "共享内的目录不存在，请核对连接根目录。";
        }
        default -> { }
      }
      break;
    }
    if (code.equals("SMB_OPERATION_FAILED")) {
      if (chain.stream().anyMatch(EOFException.class::isInstance)) {
        code = "SMB_CONNECTION_CLOSED";
        hint = stage == Stage.NEGOTIATE
            ? "对端提前关闭连接。请确认连接的是 SMB 服务端口，默认 445；5000/5001 通常是 NAS 网页管理端口。"
            : "对端提前关闭连接。请检查网络、NAS SMB 服务及服务器日志。";
      } else if (chain.stream().anyMatch(SocketTimeoutException.class::isInstance)) {
        code = "SMB_TIMEOUT";
        hint = "连接超时，请检查网络、SMB 服务和防火墙。";
      } else if (chain.stream().anyMatch(ConnectException.class::isInstance)) {
        code = "SMB_UNREACHABLE";
        hint = "无法连接，请确认 NAS 已开启 SMB 服务，默认端口为 445。";
      } else if (chain.stream().anyMatch(UnknownHostException.class::isInstance)) {
        code = "SMB_HOST_NOT_FOUND";
        hint = "无法解析主机地址，请核对主机名或 IP。";
      }
    }
    String target = endpoint.host + ":" + (endpoint.port > 0 ? endpoint.port : 445);
    return new SmbFailure(code, stage, "SMB " + stage.label + "失败（" + target + "）。" + hint, cause);
  }
}
