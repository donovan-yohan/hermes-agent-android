import ctypes as c, os, subprocess
assert os.environ['DISPLAY'] == ':187', 'Own isolated display only'
x=c.CDLL('libX11.so.6');xt=c.CDLL('libXtst.so.6')
x.XOpenDisplay.restype=c.c_void_p;x.XOpenDisplay.argtypes=[c.c_char_p];d=x.XOpenDisplay(None)
x.XGetInputFocus.argtypes=[c.c_void_p,c.POINTER(c.c_ulong),c.POINTER(c.c_int)]
f=c.c_ulong();r=c.c_int();x.XGetInputFocus(d,c.byref(f),c.byref(r))
info=subprocess.check_output(['xwininfo','-id',str(f.value)],text=True)
print(info)
assert any(t in info for t in ['Open File','Open','Choose']), 'Focus must be native picker'
x.XKeysymToKeycode.argtypes=[c.c_void_p,c.c_ulong];x.XKeysymToKeycode.restype=c.c_uint
xt.XTestFakeKeyEvent.argtypes=[c.c_void_p,c.c_uint,c.c_int,c.c_ulong]
k=x.XKeysymToKeycode(d,0xff1b)
xt.XTestFakeKeyEvent(d,k,1,0);xt.XTestFakeKeyEvent(d,k,0,0)
x.XFlush.argtypes=[c.c_void_p];x.XFlush(d)
