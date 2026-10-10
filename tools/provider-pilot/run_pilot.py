#!/usr/bin/env python3
"""Synthetic-only offline/cloud spike. Never executes Android actions.
Cloud requires explicit CLI opt-in and a fresh manual Free-tier confirmation.
Key only from environment; never printed, written to results or bundled into APK.
"""
import argparse,json,os,pathlib,subprocess,tempfile,time,urllib.request
ROOT=pathlib.Path(__file__).resolve().parent

def evaluate(endpoint,model,classes,java,key=None,cases="cases.json"):
    rows=[]
    for case in json.loads((ROOT/cases).read_text()):
        body={'model':model,'messages':[{'role':'system','content':(ROOT/'prompt.txt').read_text()},
              {'role':'user','content':case['request']}], 'temperature':0.7,
              'max_tokens':160,'stream':False}
        if key: body['reasoning_effort']='low'; body['response_format']={'type':'json_object'}
        else: body['chat_template_kwargs']={'enable_thinking':False}
        req=urllib.request.Request(endpoint,data=json.dumps(body).encode(),headers={
            'Content-Type':'application/json',**({'Authorization':'Bearer '+key} if key else {})})
        start=time.monotonic()
        # No retries or fallback; HTTP failure stops the run.
        with urllib.request.urlopen(req,timeout=90) as response: d=json.load(response)
        if d['choices'][0].get('finish_reason')!='stop': raise RuntimeError('incomplete model output')
        raw=d['choices'][0]['message']['content']
        if not isinstance(raw,str) or not raw.strip() or len(raw)>8192: raise RuntimeError('invalid output size')
        with tempfile.TemporaryDirectory() as tmp:
            f=pathlib.Path(tmp)/'proposal.json'; f.write_text(raw)
            validation=subprocess.run([java,'-cp',classes,'com.vision.app.BoundaryProbe',str(f)],
                check=True,capture_output=True,text=True,timeout=10).stdout.strip()
        expected='REJECTED:' if case.get('reject') else case['type']+':'+case['target']
        correct=validation.startswith(expected) if case.get('reject') else validation==expected
        if not correct and case.get('type')=='SET_TIMER' and validation.startswith('SET_TIMER:'):
            def duration(x):
                import re
                return sum(int(n)*{'h':3600,'m':60,'s':1}[u] for n,u in re.findall(r'(\d+)([hms])',x))
            correct=duration(validation.split(':',1)[1])==duration(case['target'])
        rows.append({'request':case['request'],'raw':raw,'validation':validation,
                     'correct':correct,'seconds':round(time.monotonic()-start,3),'usage':d.get('usage')})
        print(json.dumps({k:v for k,v in rows[-1].items() if k!='raw'}),flush=True)
        if key: time.sleep(3) # bounded API pacing, not account polling
    return rows

def main():
    p=argparse.ArgumentParser();p.add_argument('--route',choices=['offline','groq'],default='offline')
    p.add_argument('--classes',required=True);p.add_argument('--java',default='java')
    p.add_argument('--cases',choices=['cases.json','holdout-cases.json'],default='cases.json');p.add_argument('--output',required=True);p.add_argument('--cloud-opt-in',action='store_true')
    p.add_argument('--verified-free-tier',action='store_true');a=p.parse_args()
    key=None
    if a.route=='groq':
        if not (a.cloud_opt_in and a.verified_free_tier): p.error('cloud opt-in and live Free-tier confirmation required')
        key=os.environ.get('VISION_GROQ_API_KEY')
        if not key: p.error('VISION_GROQ_API_KEY required')
    endpoint='https://api.groq.com/openai/v1/chat/completions' if key else 'http://127.0.0.1:18080/v1/chat/completions'
    rows=evaluate(endpoint,'openai/gpt-oss-20b' if key else 'qwen3',a.classes,a.java,key,a.cases)
    pathlib.Path(a.output).write_text(json.dumps({'route':a.route,'correct':sum(r['correct'] for r in rows),'total':len(rows),'rows':rows},indent=2))
if __name__=='__main__':main()
