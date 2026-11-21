import numpy as np, json, sys
from PIL import Image
"""Builds the colour-family masks that let the pieces' colours recolour the illustrations (see the hub's `art()`).

For each picture in app/src/main/assets/hub/img/ it writes two soft masks next to it:
  <name>-r.webp  the RED family (Steve: shirt, mug, cushion arcs, socks, the orange cat, the red game piece)
  <name>-p.webp  the PINK family (Roni: shirt, the couch, the pink game piece and squares)
Only the alpha channel matters. Skin, ink, hair and cream are in neither family, so they are never recoloured.
The families are separated by hue (red ~4-20 deg, pink ~346-356 deg) and lightness (skin shares red's hue but is far lighter).

    python3 tools/make_illustration_masks.py            # regenerate the masks
    python3 tools/make_illustration_masks.py --check D  # also write *-check.png previews into D

Needs numpy and Pillow. Run it only if the artwork changes; the masks are committed.
"""
import os
ROOT=os.path.join(os.path.dirname(os.path.abspath(__file__)),"..")
D=os.path.join(ROOT,"app","src","main","assets","hub","img")+os.sep
O=D
import sys
CHECK=sys.argv[sys.argv.index("--check")+1]+os.sep if "--check" in sys.argv else None
if CHECK: os.makedirs(CHECK,exist_ok=True)
def hsl(rgb):
    r,g,b=[rgb[...,i]/255.0 for i in range(3)]
    mx=np.maximum(np.maximum(r,g),b); mn=np.minimum(np.minimum(r,g),b); l=(mx+mn)/2; d=mx-mn
    s=np.where(d==0,0,d/(1-np.abs(2*l-1)+1e-9)); m=d>0
    rc=np.where(m&(mx==r),((g-b)/(d+1e-9))%6,0); gc=np.where(m&(mx==g)&(mx!=r),(b-r)/(d+1e-9)+2,0); bc=np.where(m&(mx==b)&(mx!=r)&(mx!=g),(r-g)/(d+1e-9)+4,0)
    return ((rc+gc+bc)*60)%360,s,l
def ramp(x,a,b):                      # 0 at a, 1 at b (either direction), smooth
    t=np.clip((x-a)/(b-a),0,1); return t*t*(3-2*t)
def masks(rgba):
    a=rgba[...,3]/255.0; h,s,l=hsl(rgba[...,:3]); hh=np.where(h>180,h-360,h)       # red is continuous around 0
    sat=ramp(s,0.10,0.22)                                                          # near-grey stays out; hue already keeps skin and cream out
    # RED family: hue ~ -2..22 deg, mid-tone. Skin (same hue, but light and paler) is kept out by lightness.
    red=ramp(hh,-8,-2)*(1-ramp(hh,20,26))*(1-ramp(l,0.66,0.74))*ramp(l,0.20,0.30)*ramp(s,0.30,0.45)
    # PINK family: hue ~ 336..358 deg, light
    pink=ramp(hh,-30,-22)*(1-ramp(hh,-4,-1))*ramp(l,0.30,0.45)*sat*(1-ramp(l,0.90,0.95))
    return red*a, pink*a
from collections import deque
def _label_small_enclosed(free,max_area):
    """Flood-fill the pixels where free is True; return a boolean image of components that are small and do not touch the border."""
    H,W=free.shape; seen=np.zeros((H,W),bool); out=np.zeros((H,W),bool)
    for y0 in range(H):
        row=free[y0]
        for x0 in np.flatnonzero(row & ~seen[y0]):
            if seen[y0,x0]: continue
            q=deque([(y0,x0)]); seen[y0,x0]=True; pts=[]; touches=False
            while q:
                y,x=q.popleft(); pts.append((y,x))
                if y==0 or x==0 or y==H-1 or x==W-1: touches=True
                for dy,dx in ((1,0),(-1,0),(0,1),(0,-1)):
                    ny,nx=y+dy,x+dx
                    if 0<=ny<H and 0<=nx<W and free[ny,nx] and not seen[ny,nx]: seen[ny,nx]=True; q.append((ny,nx))
            if not touches and len(pts)<=max_area:
                for y,x in pts: out[y,x]=True
    return out
def _dilate(b):
    o=b.copy(); o[1:]|=b[:-1]; o[:-1]|=b[1:]; o[:,1:]|=b[:,:-1]; o[:,:-1]|=b[:,1:]; return o
def fill_small_holes(m,light,max_area=700):
    """Compression noise leaves small light patches un-selected inside a region. Fill enclosed holes of at most
    max_area pixels (only their light pixels), never big enclosed areas like a face surrounded by couch."""
    holes=_label_small_enclosed(~(m>0.5),max_area)&light
    holes=_dilate(holes)&(m<0.99)&light
    return np.where(holes,np.maximum(m,1.0),m)
NOSES={"board":[(338,150,392,205),(545,108,602,176)]}
def exclude(name,m):
    for (x0,y0,x1,y1) in NOSES.get(name,[]):
        yy,xx=np.mgrid[0:m.shape[0],0:m.shape[1]]
        f=lambda v,a,b: np.clip(np.minimum(v-a,b-v)/4.0,0,1)       # 4 px feather
        box=f(xx,x0,x1)*f(yy,y0,y1); m=m*(1-box)
    return m
for name in ("couple","cats","board"):
    im=np.array(Image.open(D+name+".webp").convert("RGBA")).astype(float)
    r,p=masks(im)
    h_,s_,l_=hsl(im[...,:3]); light=(l_>0.55)&(im[...,3]>250)
    r=fill_small_holes(r,light); p=fill_small_holes(p,light)
    r=exclude(name,r); p=exclude(name,p)
    for tag,m in (("r",r),("p",p)):
        out=np.zeros(im.shape,dtype=np.uint8); out[...,:3]=255; out[...,3]=np.round(m*255).astype(np.uint8)
        Image.fromarray(out,"RGBA").save(O+f"{name}-{tag}.webp",lossless=True,quality=100,method=6)
    if CHECK:
        # a visual check: red region painted blue, pink region painted green, on the original in grey
        base=np.array(Image.open(D+name+".webp").convert("RGBA")).astype(float)
        bg=np.full(base.shape[:2]+(3,),[0xF2,0xEB,0xDD],float); al=base[...,3:4]/255; comp=base[...,:3]*al+bg*(1-al)
        gray=comp.mean(axis=2,keepdims=True).repeat(3,2)*0.55+60
        vis=gray*(1-r[...,None]*0.9-p[...,None]*0.9)+np.array([20,80,255])*r[...,None]*0.9+np.array([20,200,60])*p[...,None]*0.9
        Image.fromarray(np.clip(vis,0,255).astype(np.uint8)).save(CHECK+f"{name}-check.png")
    print(name, "red coverage %.1f%%  pink coverage %.1f%%"%(100*(r>0.5).mean(),100*(p>0.5).mean()))
