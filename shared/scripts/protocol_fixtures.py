def overlay(base, patch):
    if isinstance(base, dict) and isinstance(patch, dict):
        return {**base, **{k: overlay(base[k], v) if k in base else v for k, v in patch.items()}}
    if isinstance(base, list) and isinstance(patch, list) and len(base) == len(patch):
        return [overlay(a, b) for a, b in zip(base, patch)]
    return patch


def expand_fixture(root):
    shared = root.get('shared', {})

    def expand(v, index=0):
        if isinstance(v, list):
            return [expand(x, index) for x in v]
        if not isinstance(v, dict):
            return v
        if '$ref' in v:
            base = expand(shared[v['$ref']], index)
            return overlay(base, expand(v['override'], index)) if 'override' in v else base
        if '$repeat' in v:
            spec = v['$repeat']
            return [expand(spec['template'], i) for i in range(spec['count'])]
        if '$concat' in v:
            return [item for part in v['$concat'] for item in expand(part, index)]
        if '$linear' in v:
            spec = v['$linear']
            number = spec['start'] + spec['step'] * index
            if spec.get('string', False):
                return str(number)
            if 'prefix' in spec or 'suffix' in spec:
                return spec.get('prefix', '') + str(number).zfill(spec.get('width', 0)) + spec.get('suffix', '')
            return number
        if '$values' in v:
            return expand(v['$values'][index], index)
        if '$cycle' in v:
            return expand(v['$cycle'][index % len(v['$cycle'])], index)
        if '$text' in v:
            spec = v['$text']
            return spec.get('prefix', '') + spec['text'] * spec['count'] + spec.get('suffix', '')
        return {k: expand(child, index) for k, child in v.items()}

    return expand({k: v for k, v in root.items() if k != 'shared'})
