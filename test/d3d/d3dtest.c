#define INITGUID
#include <math.h>
#define COBJMACROS
#include <windows.h>
#include <d3d11.h>
#include <stdio.h>

static FILE *g_log;
static void L(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    if (g_log) { vfprintf(g_log, fmt, ap); fprintf(g_log, "\n"); fflush(g_log); }
    va_end(ap);
}

static ID3D11Device *dev; static ID3D11DeviceContext *ctx;
static IDXGISwapChain *sc; static ID3D11RenderTargetView *rtv;

static LRESULT CALLBACK WndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_DESTROY) { PostQuitMessage(0); return 0; }
    if (m == WM_PAINT) {
        if (ctx && rtv) {
            static float t = 0; t += 0.02f;
            float c[4] = { (float)(0.5+0.5*sin(t)), 0.2f, (float)(0.5+0.5*cos(t)), 1.0f };
            FLOAT clear[4] = { c[0], c[1], c[2], c[3] };
            ID3D11DeviceContext_ClearRenderTargetView(ctx, rtv, clear);
            IDXGISwapChain_Present(sc, 1, 0);
        }
        ValidateRect(h, NULL);
        return 0;
    }
    return DefWindowProcW(h, m, w, l);
}

int WINAPI wWinMain(HINSTANCE hi, HINSTANCE hp, PWSTR cmd, int show) {
    g_log = fopen("C:\\d3dtest.txt", "w");
    L("=== d3dtest start ===");

    WNDCLASSW wc = {0};
    wc.lpfnWndProc = WndProc; wc.hInstance = hi; wc.lpszClassName = L"d3dtestcls";
    RegisterClassW(&wc);
    HWND hwnd = CreateWindowExW(0, L"d3dtestcls", L"D3D11 Test", WS_OVERLAPPEDWINDOW|WS_VISIBLE,
                                50, 50, 900, 600, NULL, NULL, hi, NULL);
    L("hwnd=%p", hwnd);

    DXGI_SWAP_CHAIN_DESC sd = {0};
    sd.BufferCount = 2;
    sd.BufferDesc.Width = 900; sd.BufferDesc.Height = 600;
    sd.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.OutputWindow = hwnd; sd.SampleDesc.Count = 1;
    sd.Windowed = TRUE; sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

    D3D_FEATURE_LEVEL fl;
    HRESULT hr = D3D11CreateDeviceAndSwapChain(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0, NULL, 0,
        D3D11_SDK_VERSION, &sd, &sc, &dev, &fl, &ctx);
    L("D3D11CreateDeviceAndSwapChain hr=0x%08lx featurelevel=0x%x", (unsigned long)hr, (unsigned)fl);
    if (FAILED(hr)) { L("FAILED to create device"); return 1; }

    IDXGIDevice *dxdev = NULL; IDXGIAdapter *ad = NULL; DXGI_ADAPTER_DESC adesc;
    if (SUCCEEDED(ID3D11Device_QueryInterface(dev, &IID_IDXGIDevice, (void**)&dxdev))) {
        if (SUCCEEDED(IDXGIDevice_GetAdapter(dxdev, &ad)) && SUCCEEDED(IDXGIAdapter_GetDesc(ad, &adesc))) {
            char nm[256]; WideCharToMultiByte(CP_ACP, 0, adesc.Description, -1, nm, 256, NULL, NULL);
            L("adapter=%s vendor=0x%x device=0x%x vram=%llu MB", nm, adesc.VendorId, adesc.DeviceId,
              (unsigned long long)(adesc.DedicatedVideoMemory >> 20));
        }
    }

    ID3D11Texture2D *bb = NULL;
    if (SUCCEEDED(IDXGISwapChain_GetBuffer(sc, 0, &IID_ID3D11Texture2D, (void**)&bb))) {
        D3D11_RENDER_TARGET_VIEW_DESC d = {0};
        d.Format = DXGI_FORMAT_R8G8B8A8_UNORM; d.ViewDimension = D3D11_RTV_DIMENSION_TEXTURE2D;
        hr = ID3D11Device_CreateRenderTargetView(dev, (ID3D11Resource*)bb, &d, &rtv);
        L("CreateRenderTargetView hr=0x%08lx", (unsigned long)hr);
    }
    L("entering loop");
    MSG msg;
    ULONGLONG t0 = GetTickCount64(); int frames = 0;
    while (GetTickCount64() - t0 < 6000) {
        while (PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) { TranslateMessage(&msg); DispatchMessageW(&msg); }
        if (ctx && rtv) {
            float c[4] = { (float)((frames%60)/60.0), 0.25f, 0.75f, 1.0f };
            ID3D11DeviceContext_ClearRenderTargetView(ctx, rtv, c);
            IDXGISwapChain_Present(sc, 1, 0);
            frames++;
        }
        Sleep(16);
    }
    L("frames=%d in 6s -> %.1f fps", frames, frames/6.0);
    L("=== done ===");
    return 0;
}
