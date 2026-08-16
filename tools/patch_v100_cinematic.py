from pathlib import Path

p=Path('app/src/main/java/com/mhsx/actorsticker/TrackingPanEffect.java')
s=p.read_text()
old='''    private float cineX = Float.NaN, cineY = Float.NaN;\n'''
new=old+'''    private float cineVx = 0f, cineVy = 0f;\n'''
if old not in s: raise SystemExit('v100 cinematic velocity field marker missing')
s=s.replace(old,new,1)
start=s.find('    private Center cinematicCenter(long t, Center target, Center anchor) {')
end=s.find('    private Center wideCenter',start)
if start<0 or end<0: raise SystemExit('v100 cinematicCenter block missing')
method='''    private Center cinematicCenter(long t, Center target, Center anchor) {\n        if (cineLastMs==Long.MIN_VALUE || t<cineLastMs || Float.isNaN(cineX)) {\n            cineLastMs=t; cineX=anchor.x; cineY=anchor.y; cineVx=0f; cineVy=0f;\n        }\n        float dt=Math.max(.001f,Math.min(.50f,(t-cineLastMs)/1000f)); cineLastMs=t;\n        float ex=target.x-cineX, ey=target.y-cineY;\n        if(Math.abs(ex)<CFG_CINE_DEAD)ex=0f;\n        if(Math.abs(ey)<CFG_CINE_DEAD*.75f)ey=0f;\n        float desiredVx=clamp(ex/Math.max(.06f,dt),-CFG_CINE_SPEED,CFG_CINE_SPEED);\n        float desiredVy=clamp(ey/Math.max(.06f,dt),-CFG_CINE_SPEED,CFG_CINE_SPEED);\n        float accel=Math.max(.03f,CFG_CINE_SPEED*1.85f);\n        float dv=accel*dt;\n        cineVx+=clamp(desiredVx-cineVx,-dv,dv);\n        cineVy+=clamp(desiredVy-cineVy,-dv,dv);\n        if(ex==0f)cineVx*=Math.max(0f,1f-dt*4.2f);\n        if(ey==0f)cineVy*=Math.max(0f,1f-dt*4.2f);\n        cineX=clamp(cineX+cineVx*dt,.05f,.95f);\n        cineY=clamp(cineY+cineVy*dt,.05f,.95f);\n        return new Center(cineX,cineY,target.confidence);\n    }\n\n'''
s=s[:start]+method+s[end:]
p.write_text(s)
print('v1.0 cinematic follow v2 acceleration limiter applied')
