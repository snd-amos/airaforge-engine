$srcDir = "C:\Users\soundye\Desktop\JE _Parkour V2\src\main\java\com\myengine"

# Symbols that a wildcard import might bring in. If ANY of these is referenced
# in the body, we keep the wildcard import. If we don't recognise the package
# we keep the import to be safe.
$wildcardPatterns = @{
    "com.google.gson"        = "\b(Gson|GsonBuilder|JsonElement|JsonObject|JsonArray|JsonParser|JsonSyntaxException|JsonSerializer|JsonDeserializer|JsonPrimitive|JsonNull|JsonParseException|JsonIOException|JsonSerializationContext|JsonDeserializationContext)\b"
    "org.lwjgl.assimp"       = "\b(Assimp|AI[A-Z]\w*)\b"
    "java.io"                = "\b(File|FileReader|FileWriter|Reader|Writer|IOException|BufferedReader|InputStream|OutputStream|ByteArrayInputStream|ByteArrayOutputStream|PrintStream|FileInputStream|FileOutputStream)\b"
    "java.util"              = "\b(List|ArrayList|Map|HashMap|Set|HashSet|UUID|Arrays|Collections|Iterator|ArrayDeque|Deque|LinkedList|TreeMap|LinkedHashMap|Collection|Comparator|Optional|Stack|Queue|Objects)\b"
    "org.lwjgl.glfw.GLFW"    = "\b(glfw[A-Z]\w*|GLFW_\w+)\b"
    "org.lwjgl.opengl.GL11"  = "\b(gl[A-Z]\w*|GL_\w+)\b"
    "org.lwjgl.opengl.GL13"  = "\b(gl[A-Z]\w*|GL_\w+)\b"
    "org.lwjgl.opengl.GL15"  = "\b(gl[A-Z]\w*|GL_\w+)\b"
    "org.lwjgl.opengl.GL20"  = "\b(gl[A-Z]\w*|GL_\w+)\b"
    "org.lwjgl.opengl.GL30"  = "\b(gl[A-Z]\w*|GL_\w+)\b"
}

function Test-ImportUsed {
    param([string]$line, [string]$body)

    if ($line -match '^\s*import\s+(static\s+)?([\w\.\*]+)\s*;\s*$') {
        $path = $Matches[2]
        $isStatic = $Matches[1] -ne $null -and $Matches[1] -ne ''

        $segs = $path -split '\.'
        $last = $segs[-1]

        if ($last -eq '*') {
            $pkg = ($segs[0..($segs.Length - 2)] -join '.')
            if ($wildcardPatterns.ContainsKey($pkg)) {
                return [bool]($body -cmatch $wildcardPatterns[$pkg])
            }
            return $true
        }

        return [bool]($body -cmatch ("\b" + [regex]::Escape($last) + "\b"))
    }
    return $true
}

$summary = @()
foreach ($file in Get-ChildItem $srcDir -Filter *.java) {
    $rawLines = [System.IO.File]::ReadAllLines($file.FullName)

    $importIndices = @()
    for ($i = 0; $i -lt $rawLines.Length; $i++) {
        if ($rawLines[$i] -match '^\s*import\s') { $importIndices += $i }
    }

    if ($importIndices.Count -eq 0) {
        continue
    }

    $bodyLines = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $rawLines.Length; $i++) {
        if (-not ($importIndices -contains $i)) { $bodyLines.Add($rawLines[$i]) | Out-Null }
    }
    $body = ($bodyLines -join "`n")

    $kept = 0
    $removed = 0
    $outLines = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $rawLines.Length; $i++) {
        $line = $rawLines[$i]
        if ($importIndices -contains $i) {
            if (Test-ImportUsed -line $line -body $body) {
                $outLines.Add($line) | Out-Null
                $kept++
            } else {
                $removed++
            }
        } else {
            $outLines.Add($line) | Out-Null
        }
    }

    # Collapse runs of >1 blank line within what was the import block, but only
    # if at least one import was removed. Keeps the file tidy.
    if ($removed -gt 0) {
        $final = New-Object System.Collections.Generic.List[string]
        $prevBlank = $false
        foreach ($l in $outLines) {
            $isBlank = ($l -match '^\s*$')
            if ($isBlank -and $prevBlank) { continue }
            $final.Add($l) | Out-Null
            $prevBlank = $isBlank
        }
        $outLines = $final
    }

    [System.IO.File]::WriteAllLines($file.FullName, $outLines)
    $summary += [pscustomobject]@{ File = $file.Name; Kept = $kept; Removed = $removed }
}

$summary | Sort-Object Removed -Descending | Format-Table -AutoSize
