"""Generate a distinguishable 880 Hz BGM fixture; never use user media."""
import argparse,hashlib,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--ffmpeg',required=True);a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
out=root/'app/src/androidTest/assets/motion_test/native_bgm_880hz_3s.m4a'
assert not out.exists(), 'Preserve the existing reproducible fixture'
args=['-hide_banner','-loglevel','error','-nostdin','-f','lavfi','-i','sine=frequency=880:sample_rate=48000:duration=3','-c:a','aac','-b:a','96k',str(out)]
subprocess.run([a.ffmpeg,*args],check=True)
manifest=dict(purpose='Controlled explicit BGM distinguishable from original 440 Hz live audio',generated=True,asset=out.name,bytes=out.stat().st_size,sha256=hashlib.sha256(out.read_bytes()).hexdigest(),frequencyHz=880,sampleRate=48000,durationSeconds=3,generator='scripts/generate_native_bgm_fixture.py',ffmpegArguments=args[:-1]+[out.name])
out.with_suffix('.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps(manifest,ensure_ascii=False))
