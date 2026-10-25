"""Compare the app inside a published YAS image with the same service built from this branch.

Usage: python3 compare_jars.py <service> <image.jar> <built.jar> <report.json>

What is compared (build time and machine details are ignored, code and content are not):
- BOOT-INF/classes/**      every class and resource (properties, SQL, templates) of the service itself
- BOOT-INF/lib/*.jar       third-party jars by name and SHA-256; our own modules (common-library, payment-paypal)
                           are opened and compared class by class, because they are rebuilt with new timestamps
- class files that differ in bytes are compared again with `javap -c -p` (the code itself), so a pure compiler
  metadata difference is reported apart from a real code difference
Ignored: META-INF/MANIFEST.MF and META-INF/maven/** (build JDK / tool versions), zip timestamps.
"""
import hashlib
import json
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

OWN_MODULES = ('common-library', 'payment-paypal')
IGNORED = ('META-INF/MANIFEST.MF',)


def entries(jar):
    out = {}
    with zipfile.ZipFile(jar) as z:
        for info in z.infolist():
            if info.is_dir() or info.filename in IGNORED or info.filename.startswith('META-INF/maven/'):
                continue
            out[info.filename] = z.read(info.filename)
    return out


def sha(data):
    return hashlib.sha256(data).hexdigest()


def javap(data):
    with tempfile.NamedTemporaryFile(suffix='.class', delete=False) as f:
        f.write(data)
        path = f.name
    try:
        text = subprocess.run(['javap', '-c', '-p', path], capture_output=True, text=True).stdout
    finally:
        Path(path).unlink()
    return '\n'.join(l for l in text.splitlines() if not l.startswith('Compiled from') and not l.startswith('Classfile'))


def compare_maps(a, b, prefix=''):
    """-> identical, code_equal (bytes differ, javap equal), different, only_in_image, only_in_build"""
    res = {'identical': 0, 'code_equal': [], 'different': [], 'only_in_image': [], 'only_in_build': []}
    for name in sorted(set(a) | set(b)):
        if name not in b:
            res['only_in_image'].append(prefix + name)
        elif name not in a:
            res['only_in_build'].append(prefix + name)
        elif a[name] == b[name]:
            res['identical'] += 1
        elif name.endswith(('classpath.idx', 'layers.idx')) and sorted(a[name].splitlines()) == sorted(b[name].splitlines()):
            # same list of jars, written in another order (Maven's resolution order); reported, not a code difference
            res.setdefault('order_only', []).append(prefix + name)
            if name.endswith('classpath.idx'):
                res['classpath_order'] = [l.decode() for l in a[name].splitlines()], [l.decode() for l in b[name].splitlines()]
        elif name.endswith('.class') and javap(a[name]) == javap(b[name]):
            res['code_equal'].append(prefix + name)
        else:
            res['different'].append(prefix + name)
    return res


def nested(data):
    with tempfile.NamedTemporaryFile(suffix='.jar', delete=False) as f:
        f.write(data)
        path = f.name
    try:
        return entries(path)
    finally:
        Path(path).unlink()


def main(service, image_jar, built_jar, report_path):
    a, b = entries(image_jar), entries(built_jar)
    own = lambda n: n.startswith('BOOT-INF/lib/') and Path(n).name.startswith(OWN_MODULES)
    classes_a = {n: d for n, d in a.items() if not n.startswith('BOOT-INF/lib/')}
    classes_b = {n: d for n, d in b.items() if not n.startswith('BOOT-INF/lib/')}
    result = {'service': service, 'app': compare_maps(classes_a, classes_b)}
    libs_a = {n: d for n, d in a.items() if n.startswith('BOOT-INF/lib/') and not own(n)}
    libs_b = {n: d for n, d in b.items() if n.startswith('BOOT-INF/lib/') and not own(n)}
    result['libs'] = compare_maps({n: sha(d).encode() for n, d in libs_a.items()},
                                  {n: sha(d).encode() for n, d in libs_b.items()})
    result['own_modules'] = {}
    for n in sorted({n for n in a if own(n)} | {n for n in b if own(n)}):
        if n in a and n in b:
            result['own_modules'][Path(n).name] = compare_maps(nested(a[n]), nested(b[n]), Path(n).name + '!/')
        else:
            result['own_modules'][Path(n).name] = {'missing_in': 'build' if n in a else 'image'}
    diffs = (result['app']['different'] + result['app']['only_in_image'] + result['app']['only_in_build']
             + result['libs']['different'] + result['libs']['only_in_image'] + result['libs']['only_in_build'])
    for m in result['own_modules'].values():
        diffs += m.get('different', []) + m.get('only_in_image', []) + m.get('only_in_build', [])
        if 'missing_in' in m:
            diffs.append('module missing in ' + m['missing_in'])
    result['same_code'] = not diffs
    result['differences'] = diffs
    Path(report_path).write_text(json.dumps(result, indent=2, ensure_ascii=False))
    order_only = result['app'].get('order_only', [])
    print(f"{service}: {'SAME CODE' if not diffs else 'DIFFERENT'} | app identical {result['app']['identical']}, "
          f"code-equal {len(result['app']['code_equal'])}, order-only {len(order_only)}, differences {len(diffs)}, "
          f"libs identical {result['libs']['identical']}")
    for d in diffs[:20]:
        print('    DIFF', d)
    for d in order_only:
        print('    ORDER-ONLY', d)
    if 'classpath_order' in result['app']:
        img, built = result['app'].pop('classpath_order')
        moved = [(i, l, built.index(l)) for i, l in enumerate(img) if built.index(l) != i]
        print(f'    classpath.idx: {len(img)} jars, {len(moved)} at another position; first: {moved[:3]}')


if __name__ == '__main__':
    main(*sys.argv[1:5])
