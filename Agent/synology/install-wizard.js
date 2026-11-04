/* DSM 7.2.2+ Package Center render-function interface. No external assets or runtime. */
(function () {
  const Vue = window.Vue;
  const Custom = SYNO.SDS.PkgManApp.Custom;
  const component = Vue.defineComponent({
    props: Custom.useHook.props,
    setup(props) {
      const hook = Custom.useHook(props);
      const server = Vue.ref(''), name = Vue.ref('SyncSrv NAS'), token = Vue.ref(''), allowHttp = Vue.ref(false);
      const checkState = (owner) => {
        owner = owner || props.getOwner();
        if (!owner) return;
        hook.checkState(owner);
        const next = owner.getButton('next');
        if (next) next.setDisabled(!/^https?:\/\/\S+$/.test(server.value) || !token.value.trim() || (server.value.startsWith('http:') && !allowHttp.value));
      };
      Vue.watchEffect(() => { server.value; token.value; allowHttp.value; checkState(); });
      return { headline:'连接 SyncSrv 备份中心', getNext:hook.getNext, checkState,
        getValues:() => [{syncsrv_server:server.value.trim(),syncsrv_name:name.value.trim()||'SyncSrv NAS',syncsrv_token:token.value.trim(),syncsrv_allow_http:allowHttp.value}],
        server,name,token,allowHttp };
    },
    render(h) {
      const field = (label,key,password) => h('v-form-item',{props:{label}},[h('v-input',{props:{value:this[key],type:password?'password':'text'},on:{input:v=>{this[key]=v}}})]);
      return h('pkg-center-step-content',[
        h('p','同一台群晖只需安装一次，可管理源目录、备份库、归档库和恢复库。目录在中心网页配置。'),
        h('v-form',[field('备份服务器地址','server'),field('设备名称','name'),field('一次性配对凭据','token',true),
          h('v-checkbox',{props:{value:this.allowHttp},on:{input:v=>{this.allowHttp=v}}},'允许可信局域网的 HTTP 测试')]),
        h('p','安装后回中心授权设备。请在 DSM 共享文件夹权限中，为系统内部用户 SyncSrvAgent 授予所用目录的读写权限。')
      ]);
    }
  });
  Custom.JsonpLoader.load({default:{name:'syncsrv_install',render:component}});
})();
