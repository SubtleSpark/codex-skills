// callgraph-analyzer 将 Go callgraph JSON 输出转换为 Mermaid 流程图
//
// 功能特性:
//   - 支持两种分析模式：down（向下）和 up（向上）
//   - down 模式：从入口函数向下遍历，查看调用了哪些函数
//   - up 模式：从目标函数向上遍历，查看被哪些函数调用
//   - 支持包名前缀过滤（严格模式：caller 和 callee 都需匹配）
//   - down 模式支持剪枝（cut）、标记（mark）和外部系统调用标识
//   - up 模式支持顶层调用者标识
//
// 使用示例:
//
//	# 向下分析（A 调用了谁）
//	go run <skill-dir>/scripts/main.go -mode=down -func="pkg.Handler" -cut="DoBidding"
//
//	# 向上分析（谁调用了 A）
//	go run <skill-dir>/scripts/main.go -mode=up -func="pkg.GetData"
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"
	"strings"
)

// Mode 表示分析模式
type Mode string

const (
	ModeDown Mode = "down" // 向下：A 调用了谁
	ModeUp   Mode = "up"   // 向上：谁调用了 A
)

// Edge 表示一条调用边
type Edge struct {
	Caller       string `json:"caller"`
	Callee       string `json:"callee"`
	Line         int    `json:"line"`
	Filename     string `json:"filename"`
	ExternalType string // "DB", "Redis", "HTTP", "MQ", "" (内部调用)
}

// CallGraph 存储调用图
// down 模式: caller -> []Edge
// up 模式: callee -> []Edge
type CallGraph map[string][]Edge

// MermaidGenerator 生成 Mermaid 流程图
type MermaidGenerator struct {
	mode      Mode
	graph     CallGraph
	prefixes  []string
	maxDepth  int
	nodeIDMap map[string]string // funcName -> nodeID (如 "A", "B", "C")
	nodeCount int
	allNodes  []string        // 按发现顺序记录所有节点
	allEdges  []string        // 所有边
	visited   map[string]bool // 已访问节点（多入口共享）
	edgeSet   map[string]bool // 已输出边（多入口共享，避免重复）

	// 共用
	entryNodes map[string]bool // 入口/目标节点（紫色粗边框标识）

	// down 模式专用
	cutPatterns   []string          // cut 节点匹配模式
	markPatterns  []string          // mark 节点匹配模式
	cutNodes      map[string]bool   // 被 cut 的节点
	markedNodes   map[string]bool   // 被 mark 的节点（包括其子节点）
	externalNodes map[string]string // 外部系统节点: funcName -> 类型 ("DB", "Redis", "HTTP", "MQ")

	// up 模式专用
	topNodes map[string]bool // 顶层调用者（没有更上层调用的节点）
}

func main() {
	// 命令行参数
	modeStr := flag.String("mode", "down", "分析模式：down(向下) / up(向上)")
	inputFile := flag.String("input", ".tmp/callgraph.json", "JSON 输入文件路径")
	funcName := flag.String("func", "", "入口/目标函数，多个用逗号分隔")
	prefixStr := flag.String("prefix", "", "包名前缀过滤，逗号分隔多个，不填则不过滤")
	outputFile := flag.String("output", ".tmp/flowchart.mmd", "输出文件路径")
	maxDepth := flag.Int("max-depth", 20, "最大遍历深度")
	cutStr := flag.String("cut", "", "[down模式] 剪枝节点(逗号分隔)，标红且不遍历子节点")
	markStr := flag.String("mark", "", "[down模式] 标记节点(逗号分隔)，标橙且子节点也标橙")
	showExternal := flag.Bool("external", true, "[down模式] 是否标记外部系统调用(DB/Redis/HTTP/MQ)")

	// 自定义帮助信息
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "callgraph-analyzer - Go 函数调用链分析工具\n\n")
		fmt.Fprintf(os.Stderr, "模式说明:\n")
		fmt.Fprintf(os.Stderr, "  down: 向下分析 - 查看函数调用了哪些其他函数\n")
		fmt.Fprintf(os.Stderr, "  up:   向上分析 - 查看函数被哪些其他函数调用\n\n")
		fmt.Fprintf(os.Stderr, "参数:\n")
		flag.PrintDefaults()
		fmt.Fprintf(os.Stderr, "\n样式说明:\n")
		fmt.Fprintf(os.Stderr, "  entry/target:   紫色粗边框 - 入口/目标函数\n")
		fmt.Fprintf(os.Stderr, "  top:            紫色粗边框 - 顶层调用者(up模式)\n")
		fmt.Fprintf(os.Stderr, "  cut:            红色 - 剪枝节点(down模式)\n")
		fmt.Fprintf(os.Stderr, "  mark:           橙色 - 标记节点(down模式)\n")
		fmt.Fprintf(os.Stderr, "  external_db:    蓝色 - 数据库调用(down模式)\n")
		fmt.Fprintf(os.Stderr, "  external_redis: 粉色 - Redis调用(down模式)\n")
		fmt.Fprintf(os.Stderr, "  external_http:  绿色 - HTTP调用(down模式)\n")
		fmt.Fprintf(os.Stderr, "  external_mq:    橙色 - 消息队列调用(down模式)\n")
	}
	flag.Parse()

	// 解析模式
	mode := Mode(*modeStr)
	if mode != ModeDown && mode != ModeUp {
		fmt.Fprintf(os.Stderr, "错误：无效的模式 %q，只支持 down 或 up\n", *modeStr)
		os.Exit(1)
	}

	// 验证必填参数
	if *inputFile == "" || *funcName == "" {
		fmt.Fprintln(os.Stderr, "错误：缺少必填参数")
		fmt.Fprintln(os.Stderr, "使用方法：")
		flag.PrintDefaults()
		fmt.Fprintln(os.Stderr, "\n示例：")
		fmt.Fprintln(os.Stderr, "  go run <skill-dir>/scripts/main.go -mode=down -func=\"pkg.Func\"")
		fmt.Fprintln(os.Stderr, "  go run <skill-dir>/scripts/main.go -mode=up -func=\"pkg.Func\"")
		os.Exit(1)
	}

	// 解析函数列表（支持逗号分隔多个）
	var funcs []string
	for _, f := range strings.Split(*funcName, ",") {
		if f = strings.TrimSpace(f); f != "" {
			funcs = append(funcs, f)
		}
	}

	// 解析前缀列表（为空则不过滤）
	var prefixes []string
	if *prefixStr != "" {
		prefixes = strings.Split(*prefixStr, ",")
		for i := range prefixes {
			prefixes[i] = strings.TrimSpace(prefixes[i])
		}
	}

	// 读取并解析 JSON 文件
	graph, err := loadCallGraph(*inputFile, prefixes, mode, *showExternal)
	if err != nil {
		fmt.Fprintf(os.Stderr, "错误：加载 JSON 文件失败: %v\n", err)
		os.Exit(1)
	}

	// 检查函数是否存在
	var validFuncs []string
	for _, fn := range funcs {
		if _, ok := graph[fn]; !ok {
			if mode == ModeDown {
				fmt.Fprintf(os.Stderr, "警告：入口函数 %q 在调用图中没有出向调用\n", fn)
			} else {
				fmt.Fprintf(os.Stderr, "警告：目标函数 %q 在调用图中没有被调用\n", fn)
			}
			fmt.Fprintln(os.Stderr, "可能的原因：")
			fmt.Fprintln(os.Stderr, "  1. 函数名拼写错误")
			fmt.Fprintln(os.Stderr, "  2. 该函数不在指定的前缀范围内")
			if mode == ModeDown {
				fmt.Fprintln(os.Stderr, "  3. 该函数没有调用其他函数")
			} else {
				fmt.Fprintln(os.Stderr, "  3. 该函数没有被其他函数调用（可能是入口点）")
			}
			fmt.Fprintln(os.Stderr, "\n尝试查找相似的函数名...")
			suggestions := findSimilar(fn, graph)
			if len(suggestions) > 0 {
				fmt.Fprintln(os.Stderr, "可能您想要的是：")
				for _, s := range suggestions {
					fmt.Fprintf(os.Stderr, "  - %s\n", s)
				}
			}
		} else {
			validFuncs = append(validFuncs, fn)
		}
	}
	if len(validFuncs) == 0 {
		fmt.Fprintln(os.Stderr, "错误：没有有效的函数")
		os.Exit(1)
	}

	// 解析 cut 和 mark 参数（仅 down 模式）
	var cutPatterns, markPatterns []string
	if mode == ModeDown {
		if *cutStr != "" {
			for _, p := range strings.Split(*cutStr, ",") {
				if p = strings.TrimSpace(p); p != "" {
					cutPatterns = append(cutPatterns, p)
				}
			}
		}
		if *markStr != "" {
			for _, p := range strings.Split(*markStr, ",") {
				if p = strings.TrimSpace(p); p != "" {
					markPatterns = append(markPatterns, p)
				}
			}
		}
	}

	// 生成 Mermaid 图
	gen := NewMermaidGenerator(mode, graph, prefixes, *maxDepth, cutPatterns, markPatterns)
	mermaid := gen.Generate(validFuncs)

	// 输出结果到文件
	err = os.WriteFile(*outputFile, []byte(mermaid), 0644)
	if err != nil {
		fmt.Fprintf(os.Stderr, "错误：写入文件失败: %v\n", err)
		os.Exit(1)
	}
	fmt.Fprintf(os.Stderr, "Mermaid 图已生成: %s\n", *outputFile)
	fmt.Fprintf(os.Stderr, "模式: %s, 节点数: %d, 边数: %d\n", mode, len(gen.nodeIDMap), len(gen.allEdges))
}

// loadCallGraph 从 JSON 文件加载调用图
func loadCallGraph(filename string, prefixes []string, mode Mode, showExternal bool) (CallGraph, error) {
	file, err := os.Open(filename)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	graph := make(CallGraph)
	scanner := bufio.NewScanner(file)

	// 增加 buffer 大小以处理长行
	buf := make([]byte, 0, 64*1024)
	scanner.Buffer(buf, 1024*1024)

	lineNum := 0
	skipped := 0

	for scanner.Scan() {
		lineNum++
		line := scanner.Text()
		if line == "" {
			continue
		}

		var edge Edge
		if err := json.Unmarshal([]byte(line), &edge); err != nil {
			fmt.Fprintf(os.Stderr, "警告：第 %d 行 JSON 解析失败: %v\n", lineNum, err)
			continue
		}

		// 过滤：caller 和 callee 都必须匹配前缀（严格模式）
		callerMatch := matchPrefix(edge.Caller, prefixes)
		calleeMatch := matchPrefix(edge.Callee, prefixes)

		// 过滤外部库的 Error 方法
		if !calleeMatch && isExternalErrorMethod(edge.Callee) {
			skipped++
			continue
		}

		if !callerMatch || !calleeMatch {
			// down 模式：检查是否是外部系统调用
			if mode == ModeDown && showExternal && callerMatch && isExternalCall(edge.Callee) {
				edge.ExternalType = getExternalType(edge.Callee)
				// 保留外部调用边
			} else {
				skipped++
				continue
			}
		}

		// 根据模式决定索引方向
		if mode == ModeDown {
			// 向下模式：以 caller 为 key
			graph[edge.Caller] = append(graph[edge.Caller], edge)
		} else {
			// 向上模式：以 callee 为 key（反向索引）
			graph[edge.Callee] = append(graph[edge.Callee], edge)
		}
	}

	if err := scanner.Err(); err != nil {
		return nil, err
	}

	// 对每个 key 的边按行号排序
	for key := range graph {
		edges := graph[key]
		sort.Slice(edges, func(i, j int) bool {
			return edges[i].Line < edges[j].Line
		})
		graph[key] = edges
	}

	fmt.Fprintf(os.Stderr, "加载完成: %d 行, 保留 %d 条边, 跳过 %d 条边\n",
		lineNum, countEdges(graph), skipped)

	return graph, nil
}

// matchPrefix 检查函数名是否匹配任一前缀
func matchPrefix(funcName string, prefixes []string) bool {
	if len(prefixes) == 0 {
		return true
	}

	// 处理方法格式：(*pkg.Type).Method 或 (pkg.Type).Method
	if strings.HasPrefix(funcName, "(") {
		end := strings.Index(funcName, ")")
		if end > 1 {
			inner := funcName[1:end]
			inner = strings.TrimPrefix(inner, "*")
			funcName = inner
		}
	}

	for _, p := range prefixes {
		if strings.HasPrefix(funcName, p) {
			return true
		}
	}
	return false
}

// 外部系统调用匹配模式
var externalPatterns = map[string][]string{
	"DB": {
		"database/sql.",
		"gorm.io/gorm.",
		"github.com/go-sql-driver/mysql.",
		"github.com/lib/pq.",
	},
	"Redis": {
		"github.com/gomodule/redigo/redis.",
		"github.com/go-redis/redis",
		"git.zuoyebang.cc/pkg/golib/v2/redis.",
	},
	"HTTP": {
		"net/http.Client",
		"github.com/go-resty/resty",
		"git.zuoyebang.cc/pkg/golib/v2/base.ApiClient",
		"git.zuoyebang.cc/pkg/golib/v2/zhttp",
	},
	"MQ": {
		"github.com/apache/rocketmq-client-go",
		"github.com/Shopify/sarama",
		"github.com/streadway/amqp",
	},
}

// isExternalErrorMethod 判断是否是外部库的 Error 方法
func isExternalErrorMethod(callee string) bool {
	return strings.HasSuffix(callee, ".Error") || strings.Contains(callee, ".Error$")
}

// getExternalType 判断 callee 是否为外部系统调用，返回类型
func getExternalType(callee string) string {
	calleeLower := strings.ToLower(callee)
	for typ, patterns := range externalPatterns {
		for _, p := range patterns {
			if strings.Contains(calleeLower, strings.ToLower(p)) {
				return typ
			}
		}
	}
	return ""
}

// isExternalCall 判断是否为外部系统调用
func isExternalCall(callee string) bool {
	return getExternalType(callee) != ""
}

// countEdges 统计边的总数
func countEdges(graph CallGraph) int {
	count := 0
	for _, edges := range graph {
		count += len(edges)
	}
	return count
}

// findSimilar 查找相似的函数名
func findSimilar(target string, graph CallGraph) []string {
	var suggestions []string
	targetLower := strings.ToLower(target)

	parts := strings.Split(target, ".")
	funcPart := ""
	if len(parts) > 0 {
		funcPart = strings.ToLower(parts[len(parts)-1])
	}

	for key := range graph {
		keyLower := strings.ToLower(key)
		if strings.Contains(keyLower, targetLower) ||
			(funcPart != "" && strings.Contains(keyLower, funcPart)) {
			suggestions = append(suggestions, key)
			if len(suggestions) >= 10 {
				break
			}
		}
	}

	return suggestions
}

// NewMermaidGenerator 创建生成器
func NewMermaidGenerator(mode Mode, graph CallGraph, prefixes []string, maxDepth int, cutPatterns, markPatterns []string) *MermaidGenerator {
	return &MermaidGenerator{
		mode:          mode,
		graph:         graph,
		prefixes:      prefixes,
		maxDepth:      maxDepth,
		nodeIDMap:     make(map[string]string),
		cutPatterns:   cutPatterns,
		markPatterns:  markPatterns,
		cutNodes:      make(map[string]bool),
		markedNodes:   make(map[string]bool),
		externalNodes: make(map[string]string),
		visited:       make(map[string]bool),
		edgeSet:       make(map[string]bool),
		entryNodes:    make(map[string]bool),
		topNodes:      make(map[string]bool),
	}
}

// matchPattern 检查函数名是否匹配任一模式（子串匹配）
func (g *MermaidGenerator) matchPattern(funcName string, patterns []string) bool {
	for _, p := range patterns {
		if strings.Contains(funcName, p) {
			return true
		}
	}
	return false
}

// getNodeID 获取或创建节点 ID
func (g *MermaidGenerator) getNodeID(funcName string) string {
	if id, ok := g.nodeIDMap[funcName]; ok {
		return id
	}

	id := g.generateID()
	g.nodeIDMap[funcName] = id
	g.allNodes = append(g.allNodes, funcName)
	return id
}

// generateID 生成递增的字母 ID
func (g *MermaidGenerator) generateID() string {
	n := g.nodeCount
	g.nodeCount++

	var id string
	for {
		id = string(rune('A'+n%26)) + id
		n = n/26 - 1
		if n < 0 {
			break
		}
	}
	return id
}

// Generate 从入口/目标函数开始生成 Mermaid 图
func (g *MermaidGenerator) Generate(funcs []string) string {
	// 记录入口/目标节点并遍历
	for _, fn := range funcs {
		g.entryNodes[fn] = true
		g.bfsTraverse(fn)
	}

	// 构建 Mermaid 输出
	var sb strings.Builder

	sb.WriteString("flowchart LR\n")

	// 输出节点定义
	for _, funcName := range g.allNodes {
		id := g.nodeIDMap[funcName]
		label := escapeMermaidLabel(funcName)

		styleClass := g.getStyleClass(funcName)
		sb.WriteString(fmt.Sprintf("    %s[\"%s\"]%s\n", id, label, styleClass))
	}

	sb.WriteString("\n")

	// 输出边
	for _, edge := range g.allEdges {
		sb.WriteString(fmt.Sprintf("    %s\n", edge))
	}

	// 输出样式定义
	g.writeStyleDefs(&sb)

	return sb.String()
}

// getStyleClass 获取节点的样式类
func (g *MermaidGenerator) getStyleClass(funcName string) string {
	// 入口/目标节点（紫色）
	if g.entryNodes[funcName] {
		return ":::entry"
	}

	if g.mode == ModeDown {
		// down 模式：cut > external > marked
		if g.cutNodes[funcName] {
			return ":::cut"
		}
		if extType, ok := g.externalNodes[funcName]; ok {
			return ":::external_" + strings.ToLower(extType)
		}
		if g.markedNodes[funcName] {
			return ":::marked"
		}
	} else {
		// up 模式：顶层节点（紫色）
		if g.topNodes[funcName] {
			return ":::top"
		}
	}

	return ""
}

// writeStyleDefs 输出样式定义
func (g *MermaidGenerator) writeStyleDefs(sb *strings.Builder) {
	hasStyles := len(g.entryNodes) > 0 || len(g.cutNodes) > 0 || len(g.markedNodes) > 0 ||
		len(g.externalNodes) > 0 || len(g.topNodes) > 0

	if !hasStyles {
		return
	}

	sb.WriteString("\n")

	if len(g.entryNodes) > 0 {
		sb.WriteString("    classDef entry fill:#e1bee7,stroke:#7b1fa2,stroke-width:3px,color:#4a148c\n")
	}

	if g.mode == ModeDown {
		if len(g.cutNodes) > 0 {
			sb.WriteString("    classDef cut fill:#ffcccc,stroke:#ff0000,color:#cc0000\n")
		}
		if len(g.markedNodes) > 0 {
			sb.WriteString("    classDef marked fill:#fff3cd,stroke:#ff9800,color:#e65100\n")
		}
		// 外部系统样式
		if len(g.externalNodes) > 0 {
			usedTypes := make(map[string]bool)
			for _, t := range g.externalNodes {
				usedTypes[t] = true
			}
			if usedTypes["DB"] {
				sb.WriteString("    classDef external_db fill:#e3f2fd,stroke:#1976d2,color:#0d47a1\n")
			}
			if usedTypes["Redis"] {
				sb.WriteString("    classDef external_redis fill:#fce4ec,stroke:#c2185b,color:#880e4f\n")
			}
			if usedTypes["HTTP"] {
				sb.WriteString("    classDef external_http fill:#e8f5e9,stroke:#388e3c,color:#1b5e20\n")
			}
			if usedTypes["MQ"] {
				sb.WriteString("    classDef external_mq fill:#fff3e0,stroke:#f57c00,color:#e65100\n")
			}
		}
	} else {
		// up 模式：顶层节点样式
		if len(g.topNodes) > 0 {
			sb.WriteString("    classDef top fill:#e1bee7,stroke:#7b1fa2,stroke-width:3px,color:#4a148c\n")
		}
	}
}

// bfsTraverse BFS 层序遍历调用图
func (g *MermaidGenerator) bfsTraverse(startFunc string) {
	if g.mode == ModeDown {
		g.bfsTraverseDown(startFunc)
	} else {
		g.bfsTraverseUp(startFunc)
	}
}

// bfsTraverseDown 向下遍历（down 模式）
func (g *MermaidGenerator) bfsTraverseDown(entry string) {
	type queueItem struct {
		funcName string
		depth    int
		fromMark bool
	}

	queue := []queueItem{{entry, 0, false}}
	g.getNodeID(entry)

	// 检查入口是否匹配 mark
	if g.matchPattern(entry, g.markPatterns) {
		g.markedNodes[entry] = true
	}

	for len(queue) > 0 {
		current := queue[0]
		queue = queue[1:]

		if g.visited[current.funcName] {
			continue
		}
		g.visited[current.funcName] = true

		if current.depth >= g.maxDepth {
			continue
		}

		// 检查当前节点是否是 cut 节点
		isCut := g.matchPattern(current.funcName, g.cutPatterns)
		if isCut {
			g.cutNodes[current.funcName] = true
			continue // cut 节点不继续遍历
		}

		// 检查当前节点是否是 mark 节点
		isMark := g.matchPattern(current.funcName, g.markPatterns)
		shouldMarkChildren := current.fromMark || isMark
		if isMark || current.fromMark {
			g.markedNodes[current.funcName] = true
		}

		// 获取该函数的所有调用
		edges := g.graph[current.funcName]
		for _, edge := range edges {
			callerID := g.getNodeID(edge.Caller)
			calleeID := g.getNodeID(edge.Callee)

			// 记录外部系统节点类型
			if edge.ExternalType != "" {
				g.externalNodes[edge.Callee] = edge.ExternalType
			}

			// 生成边
			edgeKey := fmt.Sprintf("%s --> %s", callerID, calleeID)
			if !g.edgeSet[edgeKey] {
				g.edgeSet[edgeKey] = true
				g.allEdges = append(g.allEdges, edgeKey)
			}

			// 如果 callee 未访问且不是外部调用，加入队列
			if !g.visited[edge.Callee] && edge.ExternalType == "" {
				queue = append(queue, queueItem{edge.Callee, current.depth + 1, shouldMarkChildren})
			}
		}
	}
}

// bfsTraverseUp 向上遍历（up 模式）
func (g *MermaidGenerator) bfsTraverseUp(target string) {
	type queueItem struct {
		funcName string
		depth    int
	}

	queue := []queueItem{{target, 0}}
	g.getNodeID(target)

	for len(queue) > 0 {
		current := queue[0]
		queue = queue[1:]

		if g.visited[current.funcName] {
			continue
		}
		g.visited[current.funcName] = true

		if current.depth >= g.maxDepth {
			continue
		}

		// 获取所有调用当前函数的 callers
		edges := g.graph[current.funcName]
		for _, edge := range edges {
			callerID := g.getNodeID(edge.Caller)
			currentID := g.nodeIDMap[current.funcName]

			// 边方向：caller --> current
			edgeKey := fmt.Sprintf("%s --> %s", callerID, currentID)
			if !g.edgeSet[edgeKey] {
				g.edgeSet[edgeKey] = true
				g.allEdges = append(g.allEdges, edgeKey)
			}

			// 检查 caller 是否是顶层节点
			if _, hasCallers := g.graph[edge.Caller]; !hasCallers {
				g.topNodes[edge.Caller] = true
			}

			// 继续向上遍历
			if !g.visited[edge.Caller] {
				queue = append(queue, queueItem{edge.Caller, current.depth + 1})
			}
		}
	}
}

// escapeMermaidLabel 转义 Mermaid 标签中的特殊字符
func escapeMermaidLabel(s string) string {
	s = strings.ReplaceAll(s, `"`, `#quot;`)
	return s
}
