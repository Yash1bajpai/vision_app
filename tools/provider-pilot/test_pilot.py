import importlib.util,pathlib,unittest,subprocess,sys
ROOT=pathlib.Path(__file__).resolve().parent
class GateTests(unittest.TestCase):
    def run_cli(self,*flags):
        return subprocess.run([sys.executable,str(ROOT/'run_pilot.py'),'--classes','unused','--output','unused',*flags],capture_output=True,text=True)
    def test_cloud_requires_opt_in(self):
        p=self.run_cli('--route','groq');self.assertNotEqual(0,p.returncode);self.assertIn('opt-in',p.stderr)
    def test_cloud_requires_free_confirmation(self):
        p=self.run_cli('--route','groq','--cloud-opt-in');self.assertNotEqual(0,p.returncode);self.assertIn('Free-tier',p.stderr)
    def test_cloud_requires_key(self):
        import os
        old=os.environ.pop('VISION_GROQ_API_KEY',None)
        try:
            p=self.run_cli('--route','groq','--cloud-opt-in','--verified-free-tier')
            self.assertNotEqual(0,p.returncode);self.assertIn('VISION_GROQ_API_KEY required',p.stderr)
        finally:
            if old:os.environ['VISION_GROQ_API_KEY']=old
    def test_no_arbitrary_user_input_option(self):
        p=self.run_cli('--request','private text');self.assertNotEqual(0,p.returncode)
    def test_default_is_offline(self):
        import inspect
        s=(ROOT/'run_pilot.py').read_text();self.assertIn("default='offline'",s)
if __name__=='__main__':unittest.main()
