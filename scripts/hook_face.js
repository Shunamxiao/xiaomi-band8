// 捕获官方 App 安装表盘时传给手环的 zip/bin 文件
function log(msg) { console.log('[face-hook] ' + msg); }

function dumpFile(path, tag) {
    if (!path) return;
    try {
        var File = Java.use('java.io.File');
        var f = File.$new(path);
        if (!f.exists()) { log(tag + ' missing: ' + path); return; }
        var size = f.length();
        var out = '/data/local/tmp/face_capture/' + tag + '_' + f.getName();
        var input = Java.use('java.io.FileInputStream').$new(f);
        var output = Java.use('java.io.FileOutputStream').$new(out);
        var buf = Java.array('byte', new Array(8192).fill(0));
        var n;
        while ((n = input.read(buf)) > 0) output.write(buf, 0, n);
        input.close(); output.close();
        log(tag + ' saved ' + out + ' (' + size + ' bytes) <- ' + path);
    } catch (e) { log(tag + ' dump failed: ' + e); }
}

function hookMethod(className, methodName, after) {
    try {
        var cls = Java.use(className);
        cls[methodName].overloads.forEach(function (ov) {
            ov.implementation = function () {
                var args = Array.prototype.slice.call(arguments);
                log(className + '.' + methodName + ' ' + args.map(String).join(' | '));
                var ret = ov.apply(this, arguments);
                if (after) after(args, ret);
                return ret;
            };
        });
        log('hooked ' + className + '.' + methodName);
    } catch (e) { log('hook failed ' + className + '.' + methodName + ': ' + e); }
}

Java.perform(function () {
    Java.use('java.io.File').$new('/data/local/tmp/face_capture').mkdirs();
    hookMethod('com.xiaomi.fitness.watch.face.install.FaceInstallBleImpl', 'doInstall', function (args) {
        dumpFile(String(args[0]), 'install');
    });
    hookMethod('com.xiaomi.fitness.watch.face.install.FaceInstallBleV2Impl', 'getFilePath', function (args, ret) {
        if (ret) dumpFile(String(ret.getAbsolutePath()), 'zip');
    });
    hookMethod('com.xiaomi.fitness.watch.face.install.FaceInstallBleV2Impl', 'findFile', function (args) {
        log('findFile faceId=' + args[0] + ' hash=' + args[1]);
    });
    log('ready');
});

