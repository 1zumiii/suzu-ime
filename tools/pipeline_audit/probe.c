// 整条输入链路的离线探测：用真实的 librime 加载用户目录，逐键输入，读取候选窗。
// 每个用例在 ai_rerank 关闭（N）与开启（A）下各输入一遍，输出首选及前 10 个候选。
// 上文由过滤器从 /tmp/ctx.txt 读取（见 README.md 的“测试钩子”）。
// 用法：./probe <用例.tsv> [按键间隔微秒，默认 25000]；环境变量 SKIP_N=1 只跑开启一遍。
// 用例每行：编号、领域、上文、答案、输入的字母，以制表符分隔。
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <unistd.h>
#include <rime_api.h>
static void dump(RimeApi*r,RimeSessionId s,const char*tag){
  RIME_STRUCT(RimeContext,c);
  printf("%s",tag);
  if(r->get_context(s,&c)){ for(int i=0;i<c.menu.num_candidates&&i<10;i++) printf("\t%s|%s",c.menu.candidates[i].text,c.menu.candidates[i].comment?c.menu.candidates[i].comment:""); r->free_context(&c);} 
  printf("\n"); fflush(stdout);
}
static void typeit(RimeApi*r,RimeSessionId s,const char*code,int gap){
  char k[2]={0,0};
  for(const char*p=code;*p;p++){ k[0]=*p; r->simulate_key_sequence(s,k); usleep(gap); }
}
int main(int argc,char**argv){
  const char*lua_plugin=getenv("RIME_LUA_PLUGIN")?getenv("RIME_LUA_PLUGIN"):"/Library/Input Methods/Squirrel.app/Contents/Frameworks/rime-plugins/librime-lua.dylib";
  dlopen(lua_plugin,RTLD_NOW|RTLD_GLOBAL);
  RIME_STRUCT(RimeTraits,t);
  t.shared_data_dir=getenv("RIME_SHARED")?getenv("RIME_SHARED"):"/Library/Input Methods/Squirrel.app/Contents/SharedSupport"; t.user_data_dir=getenv("RIME_USER")?getenv("RIME_USER"):"ud"; t.log_dir="log"; t.app_name="rime.probe"; t.distribution_name="probe"; t.min_log_level=2;
  const char*mods[]={"default","lua",NULL}; t.modules=mods;
  RimeApi*r=rime_get_api(); r->setup(&t); r->initialize(&t);
  RimeSessionId s=r->create_session(); r->select_schema(s,"rime_ice");
  FILE*f=fopen(argv[1],"r"); char line[4096]; int gap=argc>2?atoi(argv[2]):25000;
  while(fgets(line,sizeof line,f)){
    line[strcspn(line,"\n")]=0; char*fld[5]; int n=0; char*q=line; fld[n++]=q;
    while(n<5&&(q=strchr(q,'\t'))){*q++=0;fld[n++]=q;}
    if(n<5) continue;
    FILE*cf=fopen("/tmp/ctx.txt","w"); fputs(fld[2],cf); fclose(cf);
    char tag[256];
    if(!getenv("SKIP_N")){ r->set_option(s,"ai_rerank",0); r->clear_composition(s); usleep(20000);
    typeit(r,s,fld[4],gap); usleep(120000); snprintf(tag,sizeof tag,"%s\t%s\t%s\t%s\tN",fld[0],fld[1],fld[3],fld[4]); dump(r,s,tag);
    r->clear_composition(s);}
    r->set_option(s,"ai_rerank",1); usleep(20000);
    typeit(r,s,fld[4],gap); usleep(250000); snprintf(tag,sizeof tag,"%s\t%s\t%s\t%s\tA",fld[0],fld[1],fld[3],fld[4]); dump(r,s,tag);
    r->clear_composition(s);
  }
  r->destroy_session(s); r->finalize(); return 0; }
