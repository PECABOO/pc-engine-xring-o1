/* wintask.exe — WinPlay 里的「任务切换 + 应用面板」（ARM64，无 CRT）
 * 作用：wine 桌面不画图标、也没有任务栏，程序最小化后找不回来。
 *       本程序是常驻置顶小窗：上半列出所有可见窗口（含最小化的），双击还原；
 *       下半列出 C:\windows\wintask_apps.txt 里的应用，双击启动。 */
typedef void* HANDLE; typedef void* HWND; typedef void* HINSTANCE;
typedef unsigned long DWORD; typedef int BOOL; typedef long long LPARAM;
typedef unsigned long long WPARAM; typedef unsigned int UINT;
typedef unsigned short u16; typedef unsigned char u8;

__declspec(dllimport) HWND CreateWindowExW(DWORD,const u16*,const u16*,DWORD,int,int,int,int,HWND,void*,HINSTANCE,void*);
__declspec(dllimport) long long DefWindowProcW(HWND,UINT,WPARAM,LPARAM);
__declspec(dllimport) BOOL ShowWindow(HWND,int);
__declspec(dllimport) void UpdateWindow(HWND);
__declspec(dllimport) BOOL GetMessageW(void*,HWND,UINT,UINT);
__declspec(dllimport) BOOL TranslateMessage(const void*);
__declspec(dllimport) long long DispatchMessageW(const void*);
__declspec(dllimport) long long SendMessageW(HWND,UINT,WPARAM,LPARAM);
__declspec(dllimport) BOOL PostMessageW(HWND,UINT,WPARAM,LPARAM);
__declspec(dllimport) BOOL EnumWindows(BOOL(*)(HWND,LPARAM),LPARAM);
__declspec(dllimport) int GetWindowTextW(HWND,u16*,int);
__declspec(dllimport) int GetClassNameW(HWND,u16*,int);
__declspec(dllimport) BOOL IsWindowVisible(HWND);
__declspec(dllimport) BOOL IsIconic(HWND);
__declspec(dllimport) BOOL GetWindowPlacement(HWND,void*);
__declspec(dllimport) BOOL SetWindowPos(HWND,HWND,int,int,int,int,UINT);
__declspec(dllimport) BOOL MoveWindow(HWND,int,int,int,int,BOOL);
__declspec(dllimport) int GetSystemMetrics(int);
__declspec(dllimport) BOOL GetWindowRect(HWND,void*);
__declspec(dllimport) BOOL SetForegroundWindow(HWND);
__declspec(dllimport) int GetSystemMetrics(int);
__declspec(dllimport) BOOL GetWindowRect(HWND,void*);
__declspec(dllimport) BOOL DestroyWindow(HWND);
__declspec(dllimport) void PostQuitMessage(int);
__declspec(dllimport) DWORD GetModuleFileNameW(HINSTANCE,u16*,DWORD);
__declspec(dllimport) HANDLE CreateFileW(const u16*,DWORD,DWORD,void*,DWORD,DWORD,HANDLE);
__declspec(dllimport) BOOL ReadFile(HANDLE,void*,DWORD,DWORD*,void*);
__declspec(dllimport) void* CreateFontW(int,int,int,int,int,unsigned,unsigned,unsigned,
                                        unsigned,unsigned,unsigned,unsigned,unsigned,const u16*);
__declspec(dllimport) BOOL DeleteObject(void*);
__declspec(dllimport) void* SelectObject(void*, void*);
__declspec(dllimport) int SetBkMode(void*, int);
__declspec(dllimport) unsigned GetWindowThreadProcessId(void*, unsigned*);
__declspec(dllimport) void* OpenProcess(unsigned,int,unsigned);
__declspec(dllimport) BOOL TerminateProcess(void*,unsigned);
__declspec(dllimport) long SetWindowLongW(void*,int,long);
__declspec(dllimport) void* GetSysColorBrush(int);
__declspec(dllimport) int FrameRect(void*, const int*, void*);
__declspec(dllimport) int FillRect(void*, const int*, void*);
__declspec(dllimport) int DrawEdge(void*, int*, unsigned, unsigned);
__declspec(dllimport) int DrawTextW(void*, const u16*, int, int*, unsigned);
__declspec(dllimport) BOOL CloseHandle(HANDLE);
__declspec(dllimport) HINSTANCE GetModuleHandleW(const u16*);
__declspec(dllimport) BOOL RegisterClassW(const void*);
__declspec(dllimport) BOOL CreateProcessW(const u16*,u16*,void*,void*,BOOL,DWORD,void*,const u16*,void*,void*);
__declspec(dllimport) void ExitProcess(UINT);

#define WS_POPUP 0x80000000u
#define WS_CAPTION 0x00C00000u
#define WS_SYSMENU 0x00080000u
#define WS_VISIBLE 0x10000000u
#define WS_CHILD 0x40000000u
#define WS_VSCROLL 0x00200000u
#define WS_BORDER 0x00800000u
#define WS_EX_TOPMOST 0x00000008u
#define WS_EX_TOOLWINDOW 0x00000080u
#define LB_ADDSTRING 0x0180u
#define LB_RESETCONTENT 0x0184u
#define LB_GETCURSEL 0x0188u
#define LB_SETCURSEL 0x0186u
#define WM_COMMAND 0x0111u
#define WM_DESTROY 0x0002u
#define WM_CLOSE 0x0010u
#define SW_RESTORE 9

typedef struct { DWORD cb; u16* r1; u16* desk; u16* title;
  DWORD x,y,xs,ys,xc,yc,fill,flags; u16 show, cb2; u8* r2; HANDLE hin,hout,herr; } SIW;
typedef struct { HANDLE hp, ht; DWORD pid, tid; } PIW;
typedef struct { DWORD style; void* proc; int ce, we; HINSTANCE inst; void* icon; void* cur; void* brush; u16* menu; u16* cls; } WCW;
typedef struct { HWND hwnd; UINT msg; WPARAM wp; LPARAM lp; DWORD time; long x, y; } MSGW;

static HINSTANCE g_inst; static HWND g_list, g_self;
static HWND g_btnRun, g_btnMin, g_btnRef, g_btnExit, g_btnKill, g_btnClose, g_lbl, g_mini;
static void* g_font;
static void* g_fontMini;
static int g_collapsed; static int g_px, g_py;
static HWND g_hw[300]; static int g_hwn;
static u16 g_appname[80][256]; static u16 g_appexe[80][512]; static u16 g_appargs[80][256]; static int g_appn;
static u16 g_tmp[1024];

static int slen(const char* s){int i=0;while(s[i])i++;return i;}
static int wlen(const u16* s){int i=0;while(s[i])i++;return i;}
static void wcpy(u16* d,const u16* s,int mx){int i=0;while(s[i]&&i<mx-1){d[i]=s[i];i++;}d[i]=0;}
static void wcat(u16* d,const u16* s,int mx){int i=wlen(d),j=0;while(s[j]&&i<mx-1){d[i++]=s[j++];}d[i]=0;}
static void u8to16(const char* s,int n,u16* o,int mx){
  int i=0,j=0;
  while(i<n && j<mx-1 && s[i]){
    u8 c=(u8)s[i];
    unsigned cp; int adv;
    if(c<0x80){cp=c;adv=1;}
    else if((c>>5)==0x6 && i+1<n){cp=((c&0x1F)<<6)|((u8)s[i+1]&0x3F);adv=2;}
    else if((c>>4)==0xE && i+2<n){cp=((c&0x0F)<<12)|(((u8)s[i+1]&0x3F)<<6)|((u8)s[i+2]&0x3F);adv=3;}
    else if((c>>3)==0x1E && i+3<n){cp=((c&0x07)<<18)|(((u8)s[i+1]&0x3F)<<12)|(((u8)s[i+2]&0x3F)<<6)|((u8)s[i+3]&0x3F);adv=4;}
    else {cp='?';adv=1;}
    if(cp<0x10000) o[j++]=(u16)cp;
    else { cp-=0x10000; o[j++]=(u16)(0xD800+(cp>>10)); if(j<mx-1) o[j++]=(u16)(0xDC00+(cp&0x3FF)); }
    i+=adv;
  }
  o[j]=0;
}

static char g_file[65536];

static void load_apps(void){
  u16 path[]={'C',':','\\','w','i','n','d','o','w','s','\\','w','i','n','t','a','s','k','_','a','p','p','s','.','t','x','t',0};
  HANDLE h=CreateFileW(path,0x80000000u,1,0,3,0x80,0);
  g_appn=0;
  if(h==(HANDLE)-1 || !h) return;
  DWORD got=0; ReadFile(h,g_file,65000,&got,0); CloseHandle(h);
  if(got>65000) got=65000;
  int i=0;
  while(i<(int)got && g_appn<80){
    int ls=i; while(i<(int)got && g_file[i]!='\n') i++;
    int le=i; if(i<(int)got) i++; if(le>ls && g_file[le-1]=='\r') le--;
    if(le>ls && g_file[ls]!='#'){
      int f1=ls; while(f1<le && g_file[f1]!='\t') f1++;
      int f2=f1<le?f1+1:f1; while(f2<le && g_file[f2]!='\t') f2++;
      int f3=f2<le?f2+1:f2; while(f3<le && g_file[f3]!='\t') f3++;
      u8to16(g_file+ls, f1-ls, g_appname[g_appn], 256);
      u8to16(g_file+(f1<le?f1+1:f1), (f2-(f1<le?f1+1:f1)), g_appexe[g_appn], 512);
      u8to16(g_file+(f2<le?f2+1:f2), (le-(f2<le?f2+1:f2)), g_appargs[g_appn], 256);
      if(wlen(g_appexe[g_appn])>0) g_appn++;
    }
  }
}

static void apply_fonts(void){
  /* 列表/按钮用带 CJK 覆盖的字体，否则 APP 名称里的中文显示成方框 */
  const u16* faces[3]={L"Noto Sans CJK SC", L"Microsoft YaHei", L"MS Shell Dlg"};
  void* f=0;
  for(int k=0;k<3 && !f;k++)
    f=CreateFontW(-15,0,0,0,400,0,0,0,1,0,0,0,0,faces[k]);
  if(!f) return;
  g_font=f;
  g_fontMini=CreateFontW(-19,0,0,0,700,0,0,0,1,0,0,0,0,faces[0]);
  SendMessageW(g_list,0x0030,(long long)f,1);
  SendMessageW(g_btnRun,0x0030,(long long)f,1);
  SendMessageW(g_btnMin,0x0030,(long long)f,1);
  SendMessageW(g_btnRef,0x0030,(long long)f,1);
  SendMessageW(g_btnExit,0x0030,(long long)f,1);
  SendMessageW(g_btnKill,0x0030,(long long)f,1);
  SendMessageW(g_btnClose,0x0030,(long long)f,1);
  SendMessageW(g_lbl,0x0030,(long long)f,1);
}

static void add_item(const u16* s){ SendMessageW(g_list,LB_ADDSTRING,0,(LPARAM)s); }

static int is_min(HWND h){ UINT wp[11]; u8* q=(u8*)wp; for(unsigned k=0;k<sizeof(wp);k++) q[k]=0; wp[0]=sizeof(wp);
  if(!GetWindowPlacement(h,wp)) return 0;
  UINT sc=wp[2];
  if(sc!=2 && sc!=6 && sc!=7) return 0;
  /* Wine 里 GetWindowPlacement 的 showCmd 经常与真实状态不符（SumatraPDF 明明是
     可见的普通窗口也报 2）。用窗口矩形兜一层：被最小化的窗口通常被挪到 -32000 附近。 */
  int r[4]; if(GetWindowRect(h,r)){
    if(r[0] > -10000 && r[1] > -10000 && r[2] > r[0] && r[3] > r[1]) return 0;
  }
  return 1; }

static BOOL en_cb(HWND h, LPARAM p){
  u16 t[300]; u16 c[128];
  int tl=GetWindowTextW(h,t,299); t[tl<0?0:tl]=0;
  GetClassNameW(h,c,127); c[127]=0;
  if(h==g_self) return 1;
  if(!IsWindowVisible(h)) return 1;
  if(tl<=0) return 1;
  { const u16* bad=L"Default IME"; int i=0; while(bad[i]&&c[i]==bad[i])i++; if(!bad[i]) return 1; }
  g_tmp[0]=0;
  wcat(g_tmp,L"[W] ",1024);
  /* 桌面窗口（标题以反斜杠结尾，如 C:\users\xiaomi\）在 wine 里常报 showCmd=2，
     但它显然不是"被最小化"，不要给它打 [min]。 */
  int is_desktop = 0; { int n=0; while(t[n]) n++; if(n>0 && t[n-1]==L'\\') is_desktop=1; }
  if(!is_desktop && (IsIconic(h)||is_min(h))) wcat(g_tmp,L"[min] ",1024);
  wcat(g_tmp,t,1024);
  if(g_hwn<300){ g_hw[g_hwn++]=h; add_item(g_tmp); }
  return 1;
}

static void collapse(void);
static void expand(void);

static void collapse(void){
  if(g_collapsed) return;
  g_collapsed=1;
  ShowWindow(g_list,0); ShowWindow(g_btnRun,0); ShowWindow(g_btnMin,0);
  ShowWindow(g_btnRef,0); ShowWindow(g_btnExit,0); ShowWindow(g_btnKill,0); ShowWindow(g_btnClose,0); ShowWindow(g_lbl,0);
  MoveWindow(g_mini,0,0,64,64,1); ShowWindow(g_mini,1);
  /* 折叠态去掉标题栏，只留一个 WP 方块，避免标题被裁成「WinPl」 */
  SetWindowLongW(g_self,-16 /*GWL_STYLE*/, (long)(0x80000000u|0x10000000u)); /* WS_POPUP|WS_VISIBLE */
  SetWindowPos(g_self,0,6,6,64,64,0x0004u|0x0010u|0x0020u); /* NOZORDER|NOACTIVATE|FRAMECHANGED：与 WP 同尺寸，消掉四周白边 */
}

static void expand(void){
  if(!g_collapsed) return;
  g_collapsed=0;
  { int r[4]; r[0]=r[1]=r[2]=r[3]=0;
    int nx=g_px, ny=g_py;
    if(GetWindowRect(g_self,r)){ nx=r[0]; ny=60; }
    if(nx<0) nx=0; if(ny<0) ny=0;
    SetWindowLongW(g_self,-16 /*GWL_STYLE*/, (long)(0x00C00000u|0x80000000u|0x10000000u)); /* CAPTION|POPUP|VISIBLE */
    SetWindowPos(g_self,0,nx,ny,400,580,0x0004u|0x0010u|0x0020u); }
  ShowWindow(g_mini,0);
  MoveWindow(g_list,8,8,384,430,1);
  MoveWindow(g_btnRun,8,446,120,34,1); MoveWindow(g_btnMin,136,446,120,34,1);
  MoveWindow(g_btnRef,264,446,120,34,1); MoveWindow(g_btnExit,8,490,44,34,1);
  MoveWindow(g_btnKill,58,490,100,34,1); MoveWindow(g_btnClose,166,490,100,34,1);
  MoveWindow(g_lbl,58,532,330,20,1);
  ShowWindow(g_list,1); ShowWindow(g_btnRun,1); ShowWindow(g_btnMin,1);
  ShowWindow(g_btnRef,1); ShowWindow(g_btnExit,1); ShowWindow(g_btnKill,1); ShowWindow(g_btnClose,1); ShowWindow(g_lbl,1);
}

static void refresh(void){
  SendMessageW(g_list,LB_RESETCONTENT,0,0);
  g_hwn=0; load_apps();
  EnumWindows(en_cb,0);
  for(int i=0;i<g_appn;i++){
    g_tmp[0]=0; wcat(g_tmp,L"[A] ",1024); wcat(g_tmp,g_appname[i],1024);
    add_item(g_tmp);
  }
}

static void launch(int a){
  u16 cmd[1024]; cmd[0]=0;
  wcat(cmd,L"\"",1024); wcat(cmd,g_appexe[a],1024); wcat(cmd,L"\"",1024);
  if(wlen(g_appargs[a])){ wcat(cmd,L" ",1024); wcat(cmd,g_appargs[a],1024); }
  SIW si; PIW pi;
  u8* p=(u8*)&si; for(unsigned k=0;k<sizeof(si);k++) p[k]=0;
  si.cb=sizeof(si); si.show=1;
  CreateProcessW(g_appexe[a],cmd,0,0,0,0,0,0,&si,&pi);
}

static void run(void){
  int i=(int)SendMessageW(g_list,LB_GETCURSEL,0,0);
  if(i<0) return;
  if(i<g_hwn){ HWND h=g_hw[i]; ShowWindow(h,SW_RESTORE); SetForegroundWindow(h); }
  else { int a=i-g_hwn; if(a<g_appn) launch(a); }
}

static long long wndproc(HWND h, UINT m, WPARAM w, LPARAM l){
  if(m==0x002B /*WM_DRAWITEM*/){
    unsigned char* d=(unsigned char*)l;
    unsigned ctl=*(unsigned*)(d+4);
    void* hdc=*(void**)(d+32);
    int* rc=(int*)(d+40);
    if(ctl==1005 && hdc){
      int r2[4]={rc[0],rc[1],rc[2],rc[3]};
      FillRect(hdc,rc,GetSysColorBrush(15));
      DrawEdge(hdc,r2,5 /*EDGE_RAISED*/,0x000F /*BF_RECT*/);
      SelectObject(hdc, g_fontMini?g_fontMini:g_font);
      SetBkMode(hdc,1);
      DrawTextW(hdc,L"WP",-1,rc,0x00000001u|0x00000004u|0x00000020u|0x00002000u);
      return 1;
    }
  }
  switch(m){
    case 0x0001: /* WM_CREATE */
      g_self=h;
      g_list=CreateWindowExW(0,L"LISTBOX",L"",WS_CHILD|WS_VISIBLE|WS_VSCROLL|WS_BORDER|0x0001u,
                             8,8,384,430,h,(void*)2001,g_inst,0);
      g_btnRun=CreateWindowExW(0,L"BUTTON",L"Run / Restore",WS_CHILD|WS_VISIBLE|0x00010000u,8,446,120,34,h,(void*)1001,g_inst,0);
      g_btnMin=CreateWindowExW(0,L"BUTTON",L"Minimize",WS_CHILD|WS_VISIBLE|0x00010000u,136,446,120,34,h,(void*)1004,g_inst,0);
      g_btnRef=CreateWindowExW(0,L"BUTTON",L"Refresh",WS_CHILD|WS_VISIBLE|0x00010000u,264,446,120,34,h,(void*)1002,g_inst,0);
      g_btnExit=CreateWindowExW(0,L"BUTTON",L"<<",WS_CHILD|WS_VISIBLE|0x00010000u,8,490,44,34,h,(void*)1005,g_inst,0);
      g_lbl=CreateWindowExW(0,L"STATIC",L"[W] window   [A] app",WS_CHILD|WS_VISIBLE,58,532,330,20,h,0,g_inst,0);
      g_btnKill=CreateWindowExW(0,L"BUTTON",L"Kill",WS_CHILD|WS_VISIBLE|0x00010000u,58,490,100,34,h,(void*)1006,g_inst,0);
      /* 很多程序（SumatraPDF 3.5.2）的窗口没有关闭按钮，用 WM_CLOSE 优雅关闭 */
      g_btnClose=CreateWindowExW(0,L"BUTTON",L"Close",WS_CHILD|WS_VISIBLE|0x00010000u,166,490,100,34,h,(void*)1007,g_inst,0);
      g_mini=CreateWindowExW(0,L"BUTTON",L"WP",WS_CHILD|0x00010000u|0x0000000Bu /*BS_OWNERDRAW，无 WS_VISIBLE：只在折叠时显示*/,0,0,64,64,h,(void*)1005,g_inst,0);
      apply_fonts();
      refresh();
      return 0;
    case WM_COMMAND: {
      UINT id=(UINT)(w & 0xFFFF); UINT code=(UINT)((w>>16)&0xFFFF);
      if(id==1001){ run(); return 0; }
      if(id==1002){ refresh(); return 0; }
      if(id==1004){ int i=(int)SendMessageW(g_list,LB_GETCURSEL,0,0);
                    if(i>=0 && i<g_hwn){ ShowWindow(g_hw[i],6); } return 0; }
      if(id==1007){ int i=(int)SendMessageW(g_list,LB_GETCURSEL,0,0);
                    if(i>=0&&i<g_hwn){ PostMessageW(g_hw[i],WM_CLOSE,0,0); }
                    refresh(); }
      if(id==1006){ int i=(int)SendMessageW(g_list,LB_GETCURSEL,0,0);
                    if(i>=0 && i<g_hwn){ unsigned pid=0; GetWindowThreadProcessId(g_hw[i],&pid);
                      if(pid){ void* pr=OpenProcess(1,0,pid); if(pr){ TerminateProcess(pr,1); CloseHandle(pr); } } 
                      refresh(); }
                    return 0; }
      if(id==1005){ if(g_collapsed) expand(); else collapse(); return 0; }
      if(id==2001 && code==2){ run(); return 0; }
      return 0; }
    case WM_CLOSE: DestroyWindow(h); return 0;
    case WM_DESTROY: PostQuitMessage(0); return 0;
  }
  return DefWindowProcW(h,m,w,l);
}

void main(void){
  g_inst = GetModuleHandleW(0);
  WCW wc; u8* p=(u8*)&wc; for(unsigned k=0;k<sizeof(wc);k++) p[k]=0;
  wc.proc=(void*)wndproc; wc.inst=g_inst; wc.cls=L"WTaskPanel"; wc.brush=(void*)6;
  if(!RegisterClassW(&wc)) ExitProcess(3);
  int sw=GetSystemMetrics(0);
  (void)sw; g_px=6; g_py=60;   /* 默认出现在左上角 */
  HWND h=CreateWindowExW(WS_EX_TOPMOST|WS_EX_TOOLWINDOW, L"WTaskPanel", L"WinPlay Task Panel",
        WS_POPUP|WS_CAPTION|WS_VISIBLE, g_px, g_py, 400, 580, 0, 0, g_inst, 0);
  if(!h) ExitProcess(4);
  ShowWindow(h,1); UpdateWindow(h);
  MSGW msg;
  while(GetMessageW(&msg,0,0,0)){ TranslateMessage(&msg); DispatchMessageW(&msg); }
  ExitProcess(0);
}
