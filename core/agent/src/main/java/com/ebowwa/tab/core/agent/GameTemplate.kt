package com.ebowwa.tab.core.agent

import kotlinx.serialization.Serializable

/**
 * The game the model is allowed to *design*, not write.
 *
 * Nano reliably fails to emit runnable game code: across four attempts it produced a
 * truncated file, a frozen canvas, a premature </script> that left the game as inert text,
 * and a script that ended mid-function. Those are not prompt problems, they are capability.
 *
 * So the engine is fixed and known-good, and the model only chooses parameters. Its output
 * is small, schema-checked, and cannot produce a game that fails to run.
 */
@Serializable
data class GameSpec(
    val title: String = "Star Catcher",
    val objectName: String = "star",
    val background: String = "#111111",
    val accent: String = "#ffcc00",
    val speed: Int = 2,
    val spawnRate: Int = 45,
    val playerWidth: Int = 90,
)

object GameTemplate {

    /** Clamp every field: the model's numbers are untrusted input. */
    fun sanitise(spec: GameSpec): GameSpec = spec.copy(
        title = spec.title.take(24).ifBlank { "Star Catcher" },
        objectName = spec.objectName.take(16).ifBlank { "star" },
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
        return """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;height:100%;overflow:hidden;background:${s.background};touch-action:none}
canvas{display:block;width:100vw;height:100vh}
</style></head><body><canvas id="c"></canvas>
<script>
var c=document.getElementById('c'),x=c.getContext('2d');
function fit(){c.width=innerWidth;c.height=innerHeight}
addEventListener('resize',fit);fit();
var W=c.width,H=c.height,px=W/2,py=H-70,pw=${s.playerWidth},score=0,lives=3,timer=0,objs=[],over=false;
var SPEED=${s.speed},SPAWN=${s.spawnRate},ACCENT='${s.accent}',NAME='${s.objectName}',TITLE='${s.title}';
function spawn(){objs.push({x:Math.random()*(W-30)+15,y:-20,r:9+Math.random()*8});}
function reset(){objs=[];score=0;lives=3;timer=0;over=false;px=W/2;}
addEventListener('pointerdown',function(e){e.preventDefault();px=e.clientX;});
addEventListener('pointermove',function(e){e.preventDefault();px=e.clientX;});
function draw(){
  // The WebView may not be laid out when this script first runs, so innerWidth can be 0.
  // Size on every frame instead of trusting the initial fit().
  if(c.width!==innerWidth||c.height!==innerHeight){fit();W=c.width;H=c.height;py=H-70;}
  x.fillStyle='${s.background}';x.fillRect(0,0,W,H);
  if(over){
    x.fillStyle=ACCENT;x.font='bold 26px sans-serif';x.textAlign='center';
    x.fillText('GAME OVER',W/2,H/2-10);
    x.font='16px sans-serif';x.fillText('tap to play again',W/2,H/2+24);
    requestAnimationFrame(draw); // keep the loop alive so the restart tap still lands
    return;
  }
  timer++;
  if(timer%SPAWN===0)spawn();
  px=Math.max(pw/2,Math.min(W-pw/2,px));
  for(var i=objs.length-1;i>=0;i--){
    var o=objs[i];o.y+=SPEED+1;
    if(o.y>py-10&&o.y<py+22&&Math.abs(o.x-px)<pw/2){
      objs.splice(i,1);score++;continue;
    }
    if(o.y>H){objs.splice(i,1);lives--;if(lives<=0)over=true;continue;}
    x.beginPath();x.fillStyle=ACCENT;x.arc(o.x,o.y,o.r,0,6.283);x.fill();
  }
  x.fillStyle=ACCENT;x.fillRect(px-pw/2,py,pw,14);
  x.fillStyle='#fff';x.font='bold 20px sans-serif';x.textAlign='left';
  x.fillText(TITLE+'  '+score,16,34);
  x.textAlign='right';x.fillText('life '+lives,W-16,34);
  requestAnimationFrame(draw);
}
addEventListener('pointerdown',function(){if(over)reset();});
requestAnimationFrame(draw);
</script></body></html>
""".trimIndent()
    }
}
