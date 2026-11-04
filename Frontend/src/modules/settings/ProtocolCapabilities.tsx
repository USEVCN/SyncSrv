import { Hint } from "../../shared/Hint";
import { DataTable } from "../../shared/ui";
export function ProtocolCapabilities(){return <DataTable size="small" pagination={false} rowKey="protocol" columns={[
  {title:"协议",dataIndex:"protocol"},{title:"断点传输",dataIndex:"resume"},{title:"说明",dataIndex:"note",render:(text:string)=><Hint iconOnly label="协议能力说明" title={text}/>},
]} dataSource={[
  {protocol:"本地磁盘",resume:"读 / 写偏移续传",note:"跳过已验证数据块；拒绝符号链接与重叠根目录。"},
  {protocol:"SMB",resume:"读 / 写偏移续传",note:"强制签名，实际服务器能力与持久化语义需验收。"},
  {protocol:"SFTP",resume:"读 / 写偏移续传",note:"必须配置服务器 SHA256 主机密钥指纹。"},
  {protocol:"FTP",resume:"取决于 REST 支持",note:"回读验证偏移内容；不支持时中止，避免错误拼接。"},
  {protocol:"WebDAV",resume:"下载依赖 Range；上传整文件重试",note:"普通 PUT 无统一的可移植断点上传标准。"},
]}/>;}
