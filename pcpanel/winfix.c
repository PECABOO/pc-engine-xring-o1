/* winfix.exe —— 把跑出屏幕的顶层窗口拉回来（Wine 里很多程序记住过大尺寸）
 * ARM64 / 无 CRT。日志写 C:\winfix.log，每行:  x,y,w,h  title  [clamped]
 */
typedef unsigned short u16;
typedef unsigned int   u32;
typedef unsigned long long u64;

int   __stdcall EnumWindows(int (*cb)(void*, long long), long long lp);
int   __stdcall GetWindowTextW(void*, u16*, int);
int   __stdcall GetClassNameW(void*, u16*, int);
int   __stdcall IsWindowVisible(void*);
int   __stdcall GetSystemMetrics(int);
int   __stdcall GetWindowRect(void*, int*);
int   __stdcall SetWindowPos(void*, void*, int, int, int, int, u32);
int   __stdcall GetWindowLongW(void*, int);
int   __stdcall SetWindowLongW(void*, int, int);
void* __stdcall CreateFileW(const u16*, u32, u32, void*, u32, u32, void*);
int   __stdcall WriteFile(void*, const void*, u32, u32*, void*);
int   __stdcall CloseHandle(void*);
void  __stdcall ExitProcess(u32);
unsigned int __stdcall GetEnvironmentVariableW(const u16*, u16*, unsigned int);
void  __stdcall Sleep(u32);

static u16 g_log[260];
static void* g_f = (void*)-1;
static int g_dw, g_dh, g_n, g_fix;

static int slen(const u16* s){ int n=0; while(s[n]) n++; return n; }
static void wr(const u16* s){ u32 w; if(g_f==(void*)-1) return; WriteFile(g_f,s,(u32)(slen(s)*2),&w,0); }
static void wrn(long long v){
    u16 b[24]; int i=0; if(v<0){ wr((const u16*)L"-"); v=-v; }
    if(v==0){ b[i++]=L'0'; } else { while(v>0){ b[i++]=(u16)(L'0'+(v%10)); v/=10; } }
    u16 r[24]; int j=0; while(i>0) r[j++]=b[--i]; r[j]=0; wr(r);
}
static void title_of(void* h, u16* out, int cap){
    int n=GetWindowTextW(h,out,cap); if(n<0) n=0; if(n>=cap) n=cap-1; out[n]=0;
}

static int cb(void* h, long long lp){
    (void)lp;
    if(!IsWindowVisible(h)) return 1;
    u16 t[512], c[256]; title_of(h,t,512); GetClassNameW(h,c,256);
    if(!t[0]) return 1;
    /* 跳过桌面与自身 */
    const u16* p=c; if(p[0]==L'P'&&p[1]==L'r'&&p[2]==L'o'&&p[3]==L'g') return 1;
    if(t[0]==L'w'&&t[1]==L'i'&&t[2]==L'n'&&t[3]==L'f') return 1;
    /* 千万别碰我们自己画的窗口：任务面板是故意不要 WS_SYSMENU 的（右上角没有叉号），
       这里若"帮忙"补上 SYSMENU，叉号就会自己冒出来。 */
    const u16* cn=L"WTaskPanel";
    int same=1; for(int k=0;k<10;k++){ if(c[k]!=cn[k]){ same=0; break; } if(!cn[k]) break; }
    if(same) return 1;
    int r[4]; GetWindowRect(h,r);
    int x=r[0], y=r[1], w=r[2]-r[0], hh=r[3]-r[1];
    /* Some apps (SumatraPDF 3.5.2) create the frame without WS_SYSMENU, so Wine
       draws a caption with no close box at all. Add the standard bits back. */
    int st = GetWindowLongW(h, -16 /*GWL_STYLE*/);
    if((st & 0x00080000)==0){
        SetWindowLongW(h, -16, st | 0x00080000 /*SYSMENU*/ | 0x00020000 /*MINBOX*/ | 0x00010000 /*MAXBOX*/);
        SetWindowPos(h,0,0,0,0,0,0x0027 /*NOSIZE|NOMOVE|NOZORDER|FRAMECHANGED*/);
        st = GetWindowLongW(h, -16);
    }
    int bad = (x<0)||(y<0)||(x+w>g_dw)||(y+hh>g_dh);
    wr(L"st="); wrn(st); wr(L"  "); wrn(x); wr(L","); wrn(y); wr(L","); wrn(w); wr(L","); wrn(hh); wr(L"  "); wr(t);
    if(bad){
        int nx=x, ny=y, nw=w, nh=hh;
        if(nw>g_dw) nw=g_dw;
        if(nh>g_dh) nh=g_dh;
        if(nx<0) nx=0;
        if(ny<0) ny=0;
        if(nx+nw>g_dw) nx=g_dw-nw; if(nx<0) nx=0;
        if(ny+nh>g_dh) ny=g_dh-nh; if(ny<0) ny=0;
        SetWindowPos(h,0,nx,ny,nw,nh,0x0014 /*NOZORDER|NOACTIVATE*/);
        wr(L"   [clamped -> "); wrn(nx); wr(L","); wrn(ny); wr(L","); wrn(nw); wr(L","); wrn(nh); wr(L"]");
        g_fix++;
    }
    wr(L"\r\n");
    g_n++;
    return 1;
}

static int envnum(const u16* name, int dflt){
    u16 b[32]; unsigned int n=GetEnvironmentVariableW(name,b,32);
    if(!n) return dflt;
    int v=0,i=0; if(b[0]==L'-'){ return dflt; }
    while(i<(int)n && b[i]>=L'0' && b[i]<=L'9'){ v=v*10+(b[i]-L'0'); i++; }
    return v;
}
/* 按标题（子串，忽略大小写）强制设置窗口几何：MV_TITLE / MV_X / MV_Y / MV_W / MV_H */
static u16 g_want[256];
static int g_mvx, g_mvy, g_mvw, g_mvh, g_mvhit;
static int lower(int c){ return (c>=L'A'&&c<=L'Z') ? c+32 : c; }
static int contains(const u16* hay, const u16* needle){
    if(!needle[0]) return 0;
    for(int i=0; hay[i]; i++){
        int j=0; while(needle[j] && lower(hay[i+j])==lower(needle[j])) j++;
        if(!needle[j]) return 1;
    }
    return 0;
}
static int cb_mv(void* h, long long lp){
    (void)lp;
    if(!IsWindowVisible(h)) return 1;
    u16 t[512]; title_of(h,t,512);
    if(!contains(t,g_want)) return 1;
    SetWindowPos(h,0,g_mvx,g_mvy,g_mvw,g_mvh,0x0004 /*NOZORDER*/);
    g_mvhit=1;
    return 0;   /* 找到就停 */
}
void __stdcall main(void){
    g_dw=GetSystemMetrics(0); g_dh=GetSystemMetrics(1);
    const u16 path[]={'C',':','\\','w','i','n','f','i','x','.','l','o','g',0};
    g_f=CreateFileW(path,0x40000000,3,0,2,0x80,0);
    wr(L"desktop "); wrn(g_dw); wr(L"x"); wrn(g_dh); wr(L"\r\n");
    GetEnvironmentVariableW(L"MV_TITLE",g_want,256);
    if(g_want[0]){
        g_mvx=envnum(L"MV_X",0); g_mvy=envnum(L"MV_Y",0);
        g_mvw=envnum(L"MV_W",1600); g_mvh=envnum(L"MV_H",1068);
        EnumWindows(cb_mv,0);
        wr(L"-- mv hit "); wrn(g_mvhit); wr(L"\r\n");
    }
    {
        u16 wbuf[8]; unsigned int wn=GetEnvironmentVariableW(L"MV_WATCH",wbuf,8);
        if(wn){
            for(;;){ EnumWindows(cb,0); Sleep(3000); }
        }
    }
    EnumWindows(cb,0);
    wr(L"-- total "); wrn(g_n); wr(L", fixed "); wrn(g_fix); wr(L"\r\n");
    if(g_f!=(void*)-1) CloseHandle(g_f);
    ExitProcess(0);
}
