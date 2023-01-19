/*
 * This file is part of ProPatcher, licensed under the MIT License (MIT).
 *
 * Copyright (c) Jamie Mansfield <https://www.jamierocks.uk/>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package uk.jamierocks.propatcher.task

import groovy.io.FileType

import com.cloudbees.diff.ContextualPatch
import com.cloudbees.diff.ContextualPatch.PatchStatus
import com.cloudbees.diff.PatchException
import io.sigpipe.jbsdiff.Patch
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

class ApplyPatchesTask extends DefaultTask {

    @InputDirectory File target
    @InputDirectory File patches

    enum Mode {
        /** Update to existing file */
        CHANGE,
        /** Adding a new file */
        ADD,
        /** Deleting an existing file */
        DELETE
    }

    @TaskAction
    void doTask() {
        if (!patches.exists()) {
            patches.mkdirs() // Make sure patches directory exists
        }

        boolean failed = false
        patches.eachFileRecurse(FileType.FILES) { file ->
            if (file.path.endsWith('.patch')) {
                ContextualPatch patch = ContextualPatch.create(file, target)
                patch.patch(false).each { report ->
                    if (report.status == PatchStatus.Patched) {
                        report.originalBackupFile.delete() //lets delete the backup because spam
                    } else {
                        failed = true
                        println 'Failed to apply: ' + file
                        if (report.failure instanceof PatchException)
                            println '    ' + report.failure.message
                        else
                            report.failure.printStackTrace()
                    }
                }
            } else
            if (file.path.endsWith('.diff')) {
                def fileName = patches.toPath().relativize(file.toPath())
                        .toFile().path
                fileName = fileName.substring(0, fileName.length() - '.diff'.length())

                File oldFile = new File(target, fileName)
                File outFile = new File(target, fileName)

                FileReader reader = new FileReader(file)
                String base = reader.readLine()
                String modified = reader.readLine()
                reader.close()

                if (base == null || !base.startsWith("--- ")) throw new PatchException("Invalid diff header: " + base);
                if (modified == null || !modified.startsWith("+++ ")) throw new PatchException("Invalid diff header: " + modified);

                Mode mode = computeTargetPath(base, modified);
                if (mode == Mode.DELETE) {
                    outFile.delete()
                    return;
                }

                byte[] oldBytes = new byte[0]
                if (oldFile.exists()) {
                    FileInputStream oldIn = new FileInputStream(oldFile)
                    oldBytes = new byte[(int) oldFile.length()]
                    oldIn.read(oldBytes)
                    oldIn.close()
                }

                FileInputStream patchIn = new FileInputStream(file)
                byte[] patchBytes = new byte[(int) file.length()]
                patchIn.read(patchBytes)
                patchIn.close()

                def offset = base.getBytes().length + 1 + modified.getBytes().length + 1
                def diffBytes = Arrays.copyOfRange(patchBytes, offset, patchBytes.length)

                outFile.parentFile.mkdirs()
                outFile.createNewFile()
                FileOutputStream out = new FileOutputStream(outFile)
                Patch.patch(oldBytes, diffBytes, out)
                out.close()
            }
        }

        // Patches should always use /dev/null rather than any platform-specific locations, it should
        // be standardised across systems.
        // To the effect, we should clean up our messes - so delete any directories we make on Windows.
        def NUL = new File('/dev/null')
        if (System.getProperty('os.name').toLowerCase().contains('win') && NUL.exists())
            NUL.delete()
            
        if (failed)
            throw new RuntimeException('One or more patches failed to apply, see log for details')
    }

    private Mode computeTargetPath(String base, String modified) { //, ContextualPatch.SinglePatch patch) {
        base = base.substring("+++ ".length());
        modified = modified.substring("--- ".length());
        // first seen in mercurial diffs: base and modified paths are different: base starts with "a/" and modified starts with "b/"
        if ((base.startsWith("/dev/null") || base.startsWith("a/")) && (modified.equals("/dev/null") || modified.startsWith("b/"))) {
            if (base.startsWith("a/"))      base = base.substring(2);
            if (modified.startsWith("b/"))  modified = modified.substring(2);
        }
        base = untilTab(base).trim();
        if (base.equals("/dev/null")) {
            // "/dev/null" in base indicates a new file
//            patch.targetPath = untilTab(modified).trim();
            return Mode.ADD;
        } else {
//            patch.targetPath = base;
            return modified.startsWith("/dev/null") ? Mode.DELETE : Mode.CHANGE;
        }
    }

    private String untilTab(String base) {
        int pathEndIdx = base.indexOf('\t');
        if (pathEndIdx>0)
            base = base.substring(0, pathEndIdx);
        return base;
    }
}
