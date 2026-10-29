package com.ebowwa.tab.core.agent

import kotlinx.serialization.Serializable

/**
 * The model designs the game; this file builds it.
 *
 * Nano could not reliably write runnable game code (truncation, frozen canvases, a premature
 * </script>, a script that ended mid-function). So the engines are ours and known-good, and
 * the model's job is to pick one and set the dials. Its output is ~50 tokens of JSON that is
 * schema-checked and clamped, and no choice it can make produces a game that fails to run.
 *
 * [kind] is what gives the demo variety: four genuinely different mechanics.
 */
@Serializable
data class GameSpec(
    val kind: String = "catcher",
    val title: String = "Star Catcher",
    val objectName: String = "star",
    val background: String = "#111111",
    val accent: String = "#ffcc00",
    val speed: Int = 2,
    val spawnRate: Int = 45,
    val playerWidth: Int = 90,
)

object GameTemplate {

    val KINDS = listOf("catcher", "dodger", "shooter", "whack")

    fun sanitise(spec: GameSpec): GameSpec = spec.copy(
        kind = spec.kind.lowercase().trim().takeIf { it in KINDS } ?: "catcher",
        title = spec.title.take(24).ifBlank { "Untitled" },
        objectName = spec.objectName.take(16).ifBlank { "thing" },
        background = hex(spec.background, "#111111"),
        accent = hex(spec.accent, "#ffcc00"),
        speed = spec.speed.coerceIn(1, 6),
        spawnRate = spec.spawnRate.coerceIn(20, 120),
        playerWidth = spec.playerWidth.coerceIn(50, 160),
    )

    private fun hex(value: String, fallback: String): String =
        if (Regex("^#[0-9a-fA-F]{6}$").matches(value.trim())) value.trim() else fallback

    fun render(raw: GameSpec): String {
        val s = sanitise(raw)
        val body = when (s.kind) {
            "dodger" -> dodger(s)
            "shooter" -> shooter(s)
            "whack" -> whack(s)
            else -> catcher(s)
        }
        return """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;height:100%;overflow:hidden;background:${s.background};touch-action:none}
canvas{display:block;position:fixed;top:0;left:0}
</style></head><body><canvas id="c"></canvas>
<script>
var c=document.getElementById('c'),x=c.getContext('2d');
function fit(){c.width=innerWidth;c.height=innerHeight;c.style.width=innerWidth+'px';c.style.height=innerHeight+'px'}
addEventListener('resize',fit);fit();
var W=c.width,H=c.height,score=0,lives=3,timer=0,over=false;
var SPEED=${s.speed},SPAWN=${s.spawnRate},PW=${s.playerWidth},ACCENT='${s.accent}',BG='${s.background}',TITLE='${s.title}';
function reset(){score=0;lives=3;timer=0;over=false;}
function hud(){
  x.fillStyle='#fff';x.font='bold 20px sans-serif';x.textAlign='left';
  x.fillText(TITLE+'  '+score,16,34);
  x.textAlign='right';x.fillText('life '+lives,W-16,34);
}
function frameEnd(){requestAnimationFrame(draw);}
$body
</script></body></html>
""".trimIndent()
    }

    private fun catcher(s: GameSpec): String = """
var px=W/2,py=H-70,objs=[];
function spawn(){objs.push({x:Math.random()*(W-30)+15,y:-20,r:9+Math.random()*8});}
addEventListener('pointerdown',function(e){e.preventDefault();px=e.clientX;});
addEventListener('pointermove',function(e){e.preventDefault();px=e.clientX;});
function draw(){
  if(c.width!==innerWidth||c.height!==innerHeight){fit();W=c.width;H=c.height;py=H-70;}
  x.fillStyle=BG;x.fillRect(0,0,W,H);
  timer++;if(timer%SPAWN===0)spawn();
  px=Math.max(PW/2,Math.min(W-PW/2,px));
  for(var i=objs.length-1;i>=0;i--){
    var o=objs[i];o.y+=SPEED+1;
    if(o.y>py-10&&o.y<py+22&&Math.abs(o.x-px)<PW/2){objs.splice(i,1);score++;continue;}
    if(o.y>H){objs.splice(i,1);lives--;if(lives<=0){lives=3;score=0;}continue;}
    x.beginPath();x.fillStyle=ACCENT;x.arc(o.x,o.y,o.r,0,6.283);x.fill();
  }
  x.fillStyle=ACCENT;x.fillRect(px-PW/2,py,PW,14);
  hud();frameEnd();
}
requestAnimationFrame(draw);
"""

    private fun dodger(s: GameSpec): String = """
var px=W/2,py=H-90,objs=[],r=16;
function spawn(){objs.push({x:Math.random()*(W-30)+15,y:-20,r:10+Math.random()*10});}
addEventListener('pointerdown',function(e){e.preventDefault();px=e.clientX;});
addEventListener('pointermove',function(e){e.preventDefault();px=e.clientX;});
function draw(){
  if(c.width!==innerWidth||c.height!==innerHeight){fit();W=c.width;H=c.height;py=H-90;}
  x.fillStyle=BG;x.fillRect(0,0,W,H);
  timer++;if(timer%Math.max(12,SPAWN-25)===0)spawn();
  px=Math.max(r,Math.min(W-r,px));
  for(var i=objs.length-1;i>=0;i--){
    var o=objs[i];o.y+=SPEED+2;
    var dx=o.x-px,dy=o.y-py;
    if(dx*dx+dy*dy<(o.r+r)*(o.r+r)){objs.splice(i,1);lives--;if(lives<=0){lives=3;score=0;}continue;}
    if(o.y>H){objs.splice(i,1);score++;continue;}
    x.beginPath();x.fillStyle=ACCENT;x.arc(o.x,o.y,o.r,0,6.283);x.fill();
  }
  x.beginPath();x.fillStyle='#fff';x.arc(px,py,r,0,6.283);x.fill();
  hud();frameEnd();
}
requestAnimationFrame(draw);
"""

    private fun shooter(s: GameSpec): String = """
var px=W/2,py=H-70,objs=[],shots=[];
function spawn(){objs.push({x:Math.random()*(W-60)+30,y:30,r:14+Math.random()*10,hp:1});}
addEventListener('pointerdown',function(e){
  e.preventDefault();px=e.clientX;
  shots.push({x:px,y:py-10});
});
function draw(){
  if(c.width!==innerWidth||c.height!==innerHeight){fit();W=c.width;H=c.height;py=H-70;}
  x.fillStyle=BG;x.fillRect(0,0,W,H);
  timer++;if(timer%Math.max(25,SPAWN)===0)spawn();
  for(var j=shots.length-1;j>=0;j--){
    shots[j].y-=14;
    if(shots[j].y<0){shots.splice(j,1);continue;}
    x.fillStyle='#fff';x.fillRect(shots[j].x-2,shots[j].y,4,12);
  }
  for(var i=objs.length-1;i>=0;i--){
    var o=objs[i];o.y+=SPEED*0.7+0.6;
    for(var j=shots.length-1;j>=0;j--){
      var ddx=o.x-shots[j].x,ddy=o.y-shots[j].y;
      if(ddx*ddx+ddy*ddy<(o.r+8)*(o.r+8)){objs.splice(i,1);shots.splice(j,1);score++;break;}
    }
    if(objs[i]!==o)continue;
    if(o.y>py){objs.splice(i,1);lives--;if(lives<=0){lives=3;score=0;}continue;}
    x.beginPath();x.fillStyle=ACCENT;x.arc(o.x,o.y,o.r,0,6.283);x.fill();
  }
  x.fillStyle=ACCENT;x.fillRect(px-PW/2,py,PW,14);
  hud();frameEnd();
}
requestAnimationFrame(draw);
"""

    private fun whack(s: GameSpec): String = """
var target=null,ttl=0,pause=0;
function place(){
  var r=26+Math.random()*18;
  target={x:r+Math.random()*(W-2*r),y:r+70+Math.random()*(H-2*r-140),r:r};
  ttl=Math.max(28,70-SPEED*8);
}
place();
addEventListener('pointerdown',function(e){
  e.preventDefault();
  if(!target)return;
  var dx=e.clientX-target.x,dy=e.clientY-target.y;
  if(dx*dx+dy*dy<target.r*target.r){score++;target=null;pause=10;}
});
function draw(){
  if(c.width!==innerWidth||c.height!==innerHeight){fit();W=c.width;H=c.height;}
  x.fillStyle=BG;x.fillRect(0,0,W,H);
  if(target){
    ttl--;
    if(ttl<=0){target=null;pause=14;lives--;if(lives<=0){lives=3;score=0;}}
    else{
      var k=ttl/(Math.max(28,70-SPEED*8));
      x.beginPath();x.fillStyle=ACCENT;x.arc(target.x,target.y,target.r,0,6.283);x.fill();
      x.beginPath();x.strokeStyle='#fff';x.lineWidth=3;
      x.arc(target.x,target.y,target.r*k+2,0,6.283);x.stroke();
    }
  }else if(pause>0){pause--;if(pause===0)place();}
  hud();frameEnd();
}
requestAnimationFrame(draw);
"""
}
