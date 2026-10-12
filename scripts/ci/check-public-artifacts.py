#!/usr/bin/env python3
"""Fail-closed publication gate for an explicit set of CI artifact snapshots.

Heuristic inspection, not a guarantee against every encoding or unknown secret.
Read only proposed artifacts and neutral current-commit metadata; never open keys,
credential stores, private profiles, or files outside the CI checkout.
"""
from pathlib import Path, PurePosixPath
import hashlib
import io
import json
import os
import re
import stat
import subprocess
import sys
import tarfile
import unicodedata
import zipfile

NEUTRAL_NAME = 'Pocket diagnostics'
NEUTRAL_EMAIL = 'pocket-diagnostics@example.invalid'
# Complete reviewed public file hashes: required vendor attribution and SDK
# reference strings, not hashes of a person's name/email. Changed bytes lose
# the exception. Secret/credential/profile/path checks always remain active.
REVIEWED_PUBLIC_CONTENT_SHA256 = frozenset((
    '027a99d3d660d6cff46e6ee9364ace8dc23b3c2af617bc3b58090f1dd097d399',
    '041864dd4ad2513b8de5ae2b6edc7dc76370b2edeb26b72cecc53c55080d5cfb',
    '07d6c23ba8a246610f5e996eaeefb49fa4356d2284870400956a121d76b0dd2c',
    '086eb3a63e382bf3f678e2fc6e005864e3533250b84f4caa65c4a31e9e6e9787',
    '0ec0532f7cc08027abd8cf65f9e63ffb2e444c40ff4482940e629a6a5cbf8bb0',
    '10dcd817b72838f9eba34558738c6fbb2026034692ca552dcdc437b7f5e44e56',
    '151007e7c8a0e7d37854663f623a78f64653a7d833a136f682cc929304e97e27',
    '166cee49aeadfe304716d35f636aaa4b85f67c0660c910dad45680966e102b88',
    '1aa87742b40b2520ea2b8fe40ab7006d399410228c80ef541a4fcdaa5792a098',
    '1caf8a04aa0ce08ece6ed2c4639dae9e91984bec498866d0d67a82cf365d0b12',
    '1e765c9f7f5391e369cdbe64e4020c292478022f9da24f3ace11ce2d96d46527',
    '20c82018a878b372b198d2b1edb1fcf51721ec16f55ed47413a166ff7cbec71a',
    '20e4241858341092487714aa0ca6aa789647fbc06f7b95a42f4a3f3c3fa810aa',
    '20fec559a8027cd34926318da82ceb2c0f45404cea6cb40ec55bd966b852c14e',
    '2241508923aeef0c880b049dbecebe8bd8e983237cd4465dba251c52e47b975c',
    '22dcd7964e2074d1562c0431b0ed1cf6c32d16f53e0f487c3aef7be91616b368',
    '244f3748072dd88d1ff2e597b21f7d3e896b551ffcb003432960ee0ca9c29a2d',
    '282dc68600043049b8fb9bfa0dd0fb9ae0e6b7f543047121a96811e28198d71f',
    '2a58612fe7a0184e6f99544c963442de2fdce45296f442f60c063a0b633a1d6e',
    '2ba104cfa6aa1efaced20e0b4f12df8015b473ccc0832e2898dabf153e15b184',
    '2c9ce0dab36e3644d3cf8d0f967c8d34301e66b263d30fcb5b0a4a7d83764df2',
    '2cee960f73ca520c90142105bdb90796e1084d733711938e6b533d565aca08b2',
    '2d0fd93105fcd948fe1df940cf10f6925848690161007de948b0d23893abfe11',
    '3020278bd1a50e05d6596d770990b50a6186d2228c282a86376831d2a2ba36bd',
    '37ec838903461a720be5093b281f23872c2deb9609693f0ebfd03cb9f8a24fa4',
    '38a4ebd89cd0e02d1eeba2aa28d6f8575417e87830ddfeda872b05add5d5b8e0',
    '3bbed7f02bd921dec8df075fbe82816e6ffbb38ff457e9f8fce6d8d376362c86',
    '3bdc658d1739df8a5e8ed0649d02ab73e59b2006c78f4fee2a85992b7f5cd5d6',
    '3c4405f48f2db1ae22b07086cf058a1919e7d4756c58c6562b87db2215320449',
    '3c7a55a158984dcb9b24103b8ad9af343532cc55a283c1039f5dbfc7595b714b',
    '3d29f0dba6bf08c28f0080c6f415353ddd5ac8d68d3953c49385b8d6b2adaf24',
    '3d8e3875fe3881bfdaac645a7d72062d9c8efd0638464ff5c73e0bcf4406732c',
    '3df2aa2b1d1e31e883f3a8c15fa48de0adde3e27b08411fbcd83e37d68487423',
    '3edf45f758cc21f24a9ae5aadb96a16669685af024f3c976f76300aa8021cbc9',
    '3f0adb95edd2b8bb03204f6896781820fbd9c3fdba1c50cca389007b136d6de8',
    '4465a3bf7a8a759ea7f0fc1808eed4ac4fcde8c449aee498bb134f49d8b92f20',
    '44d18ee35fb817bb7d265027b613b6035af595a455049fd489f778b057850faf',
    '44e150d5ebf847278305db76bd72d40055023458d40cebac2e3eb48a12793584',
    '45f7f0653fa0671ae74b698a8ab8eebb921dd157d1e243169049c4734ce788ed',
    '4b2567f6f0afc04119697cdba8a7e7f40d8b97c7716a772e03dd25b4617cd0a4',
    '4d0c3e74a0c15e7b85314bf6f174ba4473d2489e8b0aa8d0498bf3c1227fa124',
    '4dcf6677cc503e184e71063de48886cf6191b707028841e1eee2c9161c9681bd',
    '4ef686847cbde54e030af22e50d37e7d91f0524c1178b2943769061220933907',
    '4f4ff9dbec37a4cf4631a22df74f0db7a4ec9d4ce75a40cce685419b5cb1f9d5',
    '50407d7fb56598f200f348ed5fa8b060cc29c9c49653a239e14c46e2c25e7dde',
    '5a99d4736703e57c6a05af766b247c41a16d5e837c974ad7ae11fa06374353bc',
    '5b831de719d172c851b7c91da630e96d6520870a90690226d695d8501850c74d',
    '5bcf4a91c0392a969d7ae851b251499ce0ba4d6777186c23291dbdb34fd58d69',
    '5bedd1321907880e83f8ab5a1146bf50dfa5e5a45cb2d62189a2a069b8a0ca0b',
    '5dd585565e873ed7ea2d264e186ab6c90bda7528a9de05180efd8a21ca8f0151',
    '5f8a7b2a0408fc0cd9e6e2e25e181df047b8ac5024ec0f68f10a55d39be94363',
    '5f8b97be9c6cc4f20929cc89ca0478189ab298ca13b59cbca45636681a8fcd5d',
    '61f29dd95f399aefc611e0b77b66782146af705a07c731c5caf73263498cb30b',
    '63aada3760407202d5aa366ffba231cfa6f24f1d22dbe6aaad04970e2930af7e',
    '67352dec5e7bc5742d012bdc7dc62c5c329d849e0c325d15b6280d170fd75896',
    '6a33ab642e28e629d107a207a177a22b3a2f38208dea4733f9c145a5aecb0e81',
    '6d909052fdb7b4eb19c18ffecf9a78162d7432a2f2cc9011d1efe5dd069d5be5',
    '6e72e4cfd16c5ebc621b471bfa49eb6a21dfb6a94a7033f6851486f5005f9db5',
    '71b2bab94965f21dd656a149432518bbce8148a969463965604ae2a3dd5f7e80',
    '76a8d3c15451770719f5ad6b817ac18cb1496ecde9b165763af2ba57cda55336',
    '7f783ac5138aa04990552b2e82d0cf513c1adf6e986a3c0ef7a3c5e1cb94e9c4',
    '7fe72abc792fd38e7b17dd0636aab83c603d5d4af093e3fd9c0ac896469f782e',
    '831eb3897c208f9449561dcd3da49e8db39c6f107df80379424f136ba6cb699f',
    '8532049b1e4081644d8dff6e4b95aad643072c7acff1f816654b944490fd2076',
    '8646ac8b7fce679a8b1b947f0ea12bc9fbbabb1d17a9ba2d7ce92afab3ba577e',
    '867259b20312d6de2ed2ae3f64de8ba50985e7c7ab33696623c5a1abb39449f6',
    '8677055fd64f91c3d070a3660004d380ffcbb6e2c7bc800da8d82d7f2c4076cc',
    '86998ddb19288374722a120ae597d25d381f81027fa0a9a4143942f4a5138521',
    '876736500bc7d547713d2ad1740f5a1379d98214fc459b80163e7d24736cdd97',
    '881d6d40a3964c766af10874618ca342c610656e7a1bba598f8c1cbefeca98af',
    '89354b77b5dc09410f891da792e0d969e112b6b18965b1ba35bdf5ebb50b0bf8',
    '8a61e310b0abc4a48ef76426cc2c32d6f3a4514f8ef031cbc0c26e739746d9dc',
    '8c1db50b513afc4dd82fca485b9f3ad8246444d5013be1e2562ace036468230b',
    '8d0287f1b8f6a655e268bfd5dfc71bff3caa31c7853cb3673fa398d3aecb1dd8',
    '90d19f2f14dacc76a75353c7aa20518e6a244bb9cf132e6ad01532f9d969fae8',
    '95f97e580ab1537c4eb4a30e20a4b83f0b5bcb2cee351731864c000290392687',
    '9622626141bcd84299507a616a383882f8407e8d69970a2bca733213131e15aa',
    '9693f30a3abd68c85d2159bf42401edcf7c01cfc8f6655c138ddb9b132c90a41',
    '98ad25339cdefa9ba93e47551b51cdb517b2c9f136676d550362f3f10a992bbc',
    '9f0bc9c9c039735fd88b61741e002758743a7dde5d03cfad6dfeed6490419dbf',
    'a14842315c52340a6f8273a610b255bfe3af765bdf669117c94d4e9f227652d7',
    'a157f3ec085d3642f88018ac22ee33dcc7f338587837e324a83acdba0233a429',
    'a2233d12ecafe1d9c7e67c7e23f700132665db23ce9e8e59afb6c02317a717fe',
    'a61e97302f6a73a56553417db6852f5a31bc2bd392de8d0f9060770890ce4211',
    'a7593d6e4fa664f18c96a0a418a4796f39a306f78ff5fb7b01668388b39afb8a',
    'adab4c126433bf991e2ac8803709e1b13f6ba3b8883308adf3bad4f496a283d4',
    'b2c689f19123dd19d2097fbfe1b260cf1ec90b7caba8f0094089863f7bd99bd0',
    'b2d62d9c09e474f8db4bc9e4eea76e328d2502e67b11416615e59182a9f07345',
    'b38196b423ffaee0c4f1737c559ca848943378a806aee16bdd1660993ecd8e05',
    'b8a923200e2b3dcd618506154eb2db4d63caa60b538e7cf695a8d9bce79751d7',
    'b90eb0c1686d9c52c583e9537f1200bb50d09244586af4901df2d22836e5b8dd',
    'c373d734fe34bdea78569ae8dee654cabbcbc84803f57344a10d8e11db473bf0',
    'c499c00940ff1a447d61bf9bcb51662d686c673195a635b2b10cb23fdc63924d',
    'c7ac379e288142cab096db9a0c93d4e37255c7a9b3cf1ecd5b1cd43646fdb10e',
    'cc6b6d7f18f5da2a3ffc62ef452e52e207624eebad7a4775839e9b9f0f9fa0fb',
    'cc6f20990c535877ca482725f9a21ba9399e3698dbc3cd1b4a75a275947383dc',
    'cd38b88f81c4d37505fd4908d2bf37eedb26eb8dd80897b3120d987a97079b22',
    'cd39eff9261eeb8580d3256b647917a21a7833194ac4d9652753c8f1726ef380',
    'd22cb1cd606d450430f07f4b485aaed40a033782c731c09ff38454300268c76e',
    'd533595453978274ab2837cc54a5f847c00121281221296e7e79a455131ed58e',
    'd78d7fae4fbf70b9061067c8a5cf35c565a398585bb2e4eae6877b888a7c9e7d',
    'd79780232cab492ca0ae2c55e6fcd15631051852a1f68aaffb7cdcfa51e47a4d',
    'dc4c7b2ef09efe6b6f927bcf3276295b4b85c549e56edbdfdb0fe80cde743e05',
    'ddb2b5b024924e5205b014fb784e5a9b3cc77ecc7f4085fb0de3d836ff75bd51',
    'de24cb85203c49568649e16c273419fa15ba361ed2e7326395690ea2b664a183',
    'e1543de77c2c34cde16b35758443e68dbcf3799a98bcade97eeb4977c8d1023d',
    'e38d648c2dd89093ee881c238d88749459091aa740d93b52bfcd5d9bb002c765',
    'e49599f6871af00a457f60206621ef2f1916415756a08cabe44750778c13c0a6',
    'e4cd56da6ba817e6ef50ceb431c3d6be07e10a24358011634849452acb353c27',
    'e4fae163e9652d293e749b6be066d5de99823d4793cc1d299d83ce7a50d5cf52',
    'e5f6842436020fdac82ea8b48e0bcb0cd936944635a2b69240a96fe5b77cdf98',
    'e6d26776976ddf29e08f7f661302aa78f9c7a98e961967c7989ce2eae9ac8d92',
    'e841894f01c499f3598c3fbb09f930b6b0cca703b65bbc984ea7a8dcbe5408b9',
    'e8978a60f6f5bf13aace45098f0f0530c0487f10b1ab200b46171e5963d90078',
    'e955a741c57afff0bb1e6b224ae3c767b160ea24d910ad7ad9301cc196889388',
    'f0f39b4b4bd474bc73fd72aa32049c645ec399171eba924bd3eaae3ed1f3f1d4',
    'f42072a57f056b3bfe05c781f24d913d9cf18a604f5a7e53008e3416fe270aff',
    'f7adbb45038afd86df55ddae6ac580c1fe7e4946cdf9175d1283db706bf309d9',
    'f93279eb36f97c4397ca4dbc3efcad09fb153bcb1f74b41152851cbf2ce7e230',
    'f9cd6947f3eb509a7e3776d7f2b7143cf8e7d92b1cfb165f04552cbe6e6732cb',
))
EMAIL_PATTERN = re.compile(rb'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}')
NAME_METADATA = re.compile(rb'(?im)(?:^|[\n\x00{,])\s*[\x22\x27]?(?:authors?|committer|maintainer|user[._-]?name|full[._-]?name)[\x22\x27]?\s*[:=]\s*([^\r\n\x00]{1,256})')
LIMIT_FILE = 128 * 1024 * 1024
LIMIT_TOTAL = 512 * 1024 * 1024
LIMIT_ENTRIES = 20000
LIMIT_DEPTH = 4
SECRET_PATTERNS = (
    rb'-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----',
    rb'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b',
    rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b',
    rb'\bxox[baprs]-[A-Za-z0-9-]{15,}\b',
    rb'\bsk-(?:proj-|ant-|svcacct-)?[A-Za-z0-9_-]{25,}\b',
    rb'\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\b',
    rb'(?i)authorization\s*[:=]\s*[\x22\x27]?bearer\s+[A-Za-z0-9._-]{16,}',
    rb'(?i)(?:api[_-]?key|access[_-]?token|auth[_-]?token|password|passwd|client[_-]?secret)\s*[:=]\s*[\x22\x27][^\x22\x27\s]{8,}[\x22\x27]',
    rb'https?://[^\s/:]+:[^\s/@]+@',
)
PRIVATE_NAMES = re.compile(
    r'(?i)(?:^|/)(?:\.ssh|\.aws|\.mozilla|\.git|\.env(?:\.[^/]*)?|'
    r'id_rsa|id_ed25519|credentials(?:\.[^/]*)?|debug\.keystore|'
    r'[^/]+\.(?:pem|p12|pfx|jks|keystore)|profiles\.ini|cookies\.sqlite|'
    r'logins\.json|key4\.db)(?:/|$)'
)
HOME_PATH = re.compile(rb'(?<![A-Za-z0-9_/])(?:/home/([A-Za-z0-9_.-]+)|/Users/([A-Za-z0-9_.-]+)|/root/dev/([A-Za-z0-9_.-]+))')
GENERIC_HOMES = {b'android', b'runner', b'user', b'guest', b'root', b'build', b'buildbot', b'jenkins', b'test'}


class Blocked(Exception):
    """The category is a fixed label; never include content, paths or identities."""


def normalized(text):
    return unicodedata.normalize('NFKD', text).casefold()


def verify_neutral_build_identity(root):
    # Inspect only the current commit: no old personal identity reference,
    # name/email denylist, identity hash, private configuration or new secret.
    result = subprocess.run(
        ['git', '-C', str(root), 'show', '-s', '--format=%an%n%ae%n%cn%n%ce', 'HEAD'],
        capture_output=True, text=True, timeout=10,
    )
    if result.returncode or result.stdout.splitlines() != [NEUTRAL_NAME, NEUTRAL_EMAIL, NEUTRAL_NAME, NEUTRAL_EMAIL]:
        raise Blocked('non-neutral-build-identity')


def safe_member(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or '..' in path.parts or '\\' in name or '\n' in name or '\r' in name:
        raise Blocked('unsafe-archive-member')
    if PRIVATE_NAMES.search(name):
        # Refuse before opening any credential/profile member contents.
        raise Blocked('credential-or-profile-member')


class Scanner:
    def __init__(self):
        self.total = 0
        self.entries = 0
        self.members = 0

    def inspect(self, data, name, depth=0):
        self.total += len(data)
        self.entries += 1
        if len(data) > LIMIT_FILE or self.total > LIMIT_TOTAL or self.entries > LIMIT_ENTRIES or depth > LIMIT_DEPTH:
            raise Blocked('inspection-budget')
        plain = data.replace(b'\0', b'')
        for pattern in SECRET_PATTERNS:
            if re.search(pattern, data) or re.search(pattern, plain):
                raise Blocked('credential-pattern')
        container = name.lower().endswith(('.apk', '.zip', '.jar', '.tar', '.tar.gz', '.tar.xz', '.tgz', '.txz')) or data.startswith(b'PK\x03\x04')
        # Inspect leaf payloads and archive metadata separately. Attribution
        # exceptions apply only to complete unchanged reviewed public bytes.
        if not container and hashlib.sha256(data).hexdigest() not in REVIEWED_PUBLIC_CONTENT_SHA256:
            for match in EMAIL_PATTERN.finditer(plain):
                if match.group().lower() != NEUTRAL_EMAIL.encode():
                    raise Blocked('personal-email-pattern')
            for match in NAME_METADATA.finditer(plain):
                value = match.group(1).strip().strip(b'"\'')
                accepted = {NEUTRAL_NAME.encode(), (NEUTRAL_NAME + ' <' + NEUTRAL_EMAIL + '>').encode()}
                if value not in accepted:
                    raise Blocked('non-neutral-name-metadata')
        for match in HOME_PATH.finditer(plain):
            if match.group(2) or match.group(3) or match.group(1) not in GENERIC_HOMES:
                raise Blocked('personal-host-path')
        suffix = name.lower()
        if suffix.endswith(('.apk', '.zip', '.jar')) or data.startswith(b'PK\x03\x04'):
            try:
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    self.inspect(archive.comment, 'archive-comment', depth + 1)
                    for member in archive.infolist():
                        self.members += 1
                        if self.members > LIMIT_ENTRIES:
                            raise Blocked('inspection-budget')
                        safe_member(member.filename)
                        self.inspect(member.filename.encode() + b'\n' + member.comment + b'\n' + member.extra, 'archive-metadata', depth + 1)
                        if member.is_dir():
                            continue
                        if member.flag_bits & 1 or member.file_size > LIMIT_FILE or stat.S_ISLNK(member.external_attr >> 16):
                            raise Blocked('uninspectable-archive-member')
                        self.inspect(archive.read(member), member.filename, depth + 1)
            except (zipfile.BadZipFile, RuntimeError, NotImplementedError):
                raise Blocked('invalid-archive') from None
        elif suffix.endswith(('.tar', '.tar.gz', '.tar.xz', '.tgz', '.txz')):
            try:
                with tarfile.open(fileobj=io.BytesIO(data), mode='r:*') as archive:
                    for member in archive:
                        self.members += 1
                        if self.members > LIMIT_ENTRIES:
                            raise Blocked('inspection-budget')
                        safe_member(member.name)
                        self.inspect(member.name.encode(), 'archive-metadata', depth + 1)
                        if member.isdir():
                            continue
                        if member.issym() or member.islnk():
                            if PRIVATE_NAMES.search(member.linkname):
                                raise Blocked('credential-or-profile-member')
                            self.inspect(member.linkname.encode(), 'link-target', depth + 1)
                            continue
                        if not member.isfile() or member.size > LIMIT_FILE:
                            raise Blocked('uninspectable-archive-member')
                        stream = archive.extractfile(member)
                        if stream is None:
                            raise Blocked('uninspectable-archive-member')
                        self.inspect(stream.read(LIMIT_FILE + 1), member.name, depth + 1)
            except (tarfile.TarError, EOFError):
                raise Blocked('invalid-archive') from None


def artifact_paths(root, mode, sha):
    out = Path('build/pocket-tawcroot-diagnostics')
    if mode == 'host':
        base = out / 'host'
        allowed = [base / phase / name for phase in ('baseline', 'trace') for name in ('build.log', 'tests.log', 'result.json')]
    elif mode == 'arm64':
        base = out / 'arm64'
        allowed = [base / name for name in ('build.log', 'libtawcroot-trace.so', 'manifest.json')]
    elif mode == 'apk':
        if not re.fullmatch('[0-9a-f]{40}', sha):
            raise Blocked('source-reference-unavailable')
        base = out / 'apk'
        apk = Path('app/build/outputs/apk/debug') / f'tawc-debug-{sha[:7]}.apk'
        allowed = [base / name for name in ('build.log', 'unit-tests.log', 'signing-check.log')] + [apk, apk.with_name('SHA256SUMS')]
        if not (root / apk).is_file() or not (root / apk.with_name('SHA256SUMS')).is_file():
            raise Blocked('required-artifact-unavailable')
    elif mode == 'companion':
        if not re.fullmatch('[0-9a-f]{40}', sha):
            raise Blocked('source-reference-unavailable')
        base = out / 'companion'
        apk = base / f'pocket-sigsys-probe-{sha[:7]}.apk'
        allowed = [base / name for name in ('prepare.log', 'build.log', 'native-tests.log', 'unit-tests.log', 'lint.log', 'signing-check.log', 'manifest.json', 'SHA256SUMS')] + [apk]
        if (root / apk).is_file():
            if not (root / base / 'manifest.json').is_file() or not (root / base / 'SHA256SUMS').is_file():
                raise Blocked('required-artifact-unavailable')
    else:
        raise Blocked('unsupported-artifact-kind')
    if not (root / base).is_dir() or (root / base).is_symlink():
        raise Blocked('required-artifact-unavailable')
    for path in (root / base).rglob('*'):
        if path.is_symlink() or (path.is_file() and path.relative_to(root) not in allowed):
            raise Blocked('unexpected-artifact-file')
    selected = [relative for relative in allowed if (root / relative).is_file()]
    if not selected:
        raise Blocked('required-artifact-unavailable')
    if mode == 'arm64' and len(selected) != len(allowed):
        raise Blocked('required-artifact-unavailable')
    for relative in selected:
        path = root / relative
        if any(part.is_symlink() for part in (path, *path.parents) if part != root.parent):
            raise Blocked('artifact-symlink')
        if path.stat().st_size > LIMIT_FILE:
            raise Blocked('inspection-budget')
    return selected


def approve(root, mode, sha):
    if not re.fullmatch('[0-9a-f]{40}', sha):
        raise Blocked('source-reference-unavailable')
    selected = artifact_paths(root, mode, sha)
    scanner = Scanner()
    snapshots = []
    for relative in selected:
        data = (root / relative).read_bytes()
        scanner.inspect(data, str(relative))
        snapshots.append((relative, data))
    if mode in ('apk', 'companion'):
        # Failure-only companion logs may be approved, but an APK requires the
        # successful signed manifest and exact checksum. Never publish a partial APK.
        if mode == 'companion' and not any(relative.suffix == '.apk' for relative, _ in snapshots):
            pass
        else:
            apk = next((relative, data) for relative, data in snapshots if relative.suffix == '.apk')
            checksum = next(data for relative, data in snapshots if relative.name == 'SHA256SUMS')
            expected = f'{hashlib.sha256(apk[1]).hexdigest()}  {apk[0].name}\n'.encode()
            if checksum != expected:
                raise Blocked('artifact-checksum-mismatch')
            if mode == 'companion':
                manifest = json.loads(next(data for relative, data in snapshots if relative.name == 'manifest.json'))
                if manifest.get('source_commit') != sha or manifest.get('apk_sha256') != hashlib.sha256(apk[1]).hexdigest() or manifest.get('package') != 'me.phie.tawc.sigsysprobe' or manifest.get('certificate_sha256') != '1d023213c6fa11f51455508f595ddbd80a906d761e85dc9f21738c017d911afd':
                    raise Blocked('artifact-manifest-mismatch')
    # Upload only frozen, inspected bytes. No file is copied after its check.
    stage = root / 'build/public-artifacts' / mode
    if stage.exists() or any(parent.is_symlink() for parent in stage.parents):
        raise Blocked('snapshot-location-unavailable')
    stage.mkdir(parents=True, mode=0o700)
    paths = []
    manifest = {'kind': mode, 'source_commit': sha, 'inspection': 'Heuristic privacy gate; not an assurance against unknown encodings.', 'files': []}
    for relative, data in snapshots:
        destination = stage / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        destination.chmod(0o600)
        paths.append(str(destination.relative_to(root)))
        manifest['files'].append({'path': str(relative), 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    manifest_path = stage / 'approved-files.json'
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    manifest_path.chmod(0o600)
    paths.append(str(manifest_path.relative_to(root)))
    return paths


def main():
    try:
        root = Path(__file__).resolve().parents[2]
        if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_WORKSPACE') != str(root) or len(sys.argv) != 2:
            raise Blocked('recognized-ci-required')
        verify_neutral_build_identity(root)
        paths = approve(root, sys.argv[1], os.environ.get('GITHUB_SHA', ''))
        output = os.environ.get('GITHUB_OUTPUT')
        if not output:
            raise Blocked('ci-output-unavailable')
        with open(output, 'a') as stream:
            stream.write('approved=true\npaths<<PUBLIC_ARTIFACT_PATHS\n' + '\n'.join(paths) + '\nPUBLIC_ARTIFACT_PATHS\n')
        print(f'Public artifact privacy gate passed; approved files: {len(paths)}')
        return 0
    except Blocked as error:
        print(f'Public artifact privacy gate blocked: {error}', file=sys.stderr)
    except Exception:
        # Parser, filesystem and Git errors fail closed without printing values.
        print('Public artifact privacy gate blocked: inspection-unavailable', file=sys.stderr)
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
