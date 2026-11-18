#define INITGUID
#include <windows.h>
#include <stdio.h>

typedef const unsigned char* (WINAPI *PFNGETSTRING)(unsigned int);
typedef void* (WINAPI *PFNCREATECTXATTRIB)(HDC, HGLRC, const int*);

static FILE *L;
static void logf_(const char *k, const char *v){ if(L){ fprintf(L,"%s = %s\n",k,v); fflush(L);} }

int WINAPI wWinMain(HINSTANCE hi, HINSTANCE hp, PWSTR cmd, int show){
    L = fopen("C:\\glinfo.txt","w");
    if(!L) return 2;
    WNDCLASSW wc={0}; wc.lpfnWndProc=DefWindowProcW; wc.hInstance=hi; wc.lpszClassName=L"glcls";
    RegisterClassW(&wc);
    HWND h = CreateWindowExW(0,L"glcls",L"gl",WS_OVERLAPPEDWINDOW,0,0,320,240,NULL,NULL,hi,NULL);

    PIXELFORMATDESCRIPTOR pfd={0};
    pfd.nSize=sizeof(pfd); pfd.nVersion=1; pfd.dwFlags=PFD_DRAW_TO_WINDOW|PFD_SUPPORT_OPENGL|PFD_DOUBLEBUFFER;
    pfd.iPixelType=PFD_TYPE_RGBA; pfd.cColorBits=32; pfd.cDepthBits=24;
    HDC dc = GetDC(h);
    int pf = ChoosePixelFormat(dc,&pfd);
    fprintf(L,"ChoosePixelFormat=%d\n",pf); fflush(L);
    if(!pf){ return 3; }
    SetPixelFormat(dc,pf,&pfd);
    HGLRC rc = wglCreateContext(dc);
    fprintf(L,"wglCreateContext=%p\n",(void*)rc); fflush(L);
    if(!rc) return 4;
    if(!wglMakeCurrent(dc,rc)){ fprintf(L,"wglMakeCurrent failed\n"); fflush(L); return 5; }

    PFNGETSTRING gs = (PFNGETSTRING)wglGetProcAddress("glGetString");
    if(!gs) gs = (PFNGETSTRING)GetProcAddress(GetModuleHandleA("opengl32.dll"),"glGetString");
    if(gs){
        logf_("GL_VENDOR",   (const char*)gs(0x1F00));
        logf_("GL_RENDERER", (const char*)gs(0x1F01));
        logf_("GL_VERSION",  (const char*)gs(0x1F02));
        logf_("GL_SL_VERSION",(const char*)gs(0x8B8C));
    } else fprintf(L,"glGetString unavailable\n");

    /* 尝试 core 3.2 上下文（Minecraft 需要） */
    PFNCREATECTXATTRIB cca = (PFNCREATECTXATTRIB)wglGetProcAddress("wglCreateContextAttribsARB");
    fprintf(L,"wglCreateContextAttribsARB=%p\n",(void*)cca); fflush(L);
    if(cca){
        int attribs[] = { 0x2091,3, 0x2092,2, 0x9126,0x00000001, 0 };
        HGLRC rc32 = cca(dc, NULL, attribs);
        fprintf(L,"core3.2 ctx=%p\n",(void*)rc32); fflush(L);
        if(rc32 && wglMakeCurrent(dc,rc32) && gs){
            logf_("CORE_GL_VERSION", (const char*)gs(0x1F02));
            logf_("CORE_GL_RENDERER",(const char*)gs(0x1F01));
        }
    }
    fprintf(L,"=== end ===\n"); fflush(L);
    fclose(L);
    return 0;
}
