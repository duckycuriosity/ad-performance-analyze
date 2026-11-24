# 1. Initial conversation and give claude my idea
    Tôi có một file input với dữ liệu mẫu như sau: 
campaign_id	date	impressions	clicks	spend	conversions
CMP025	4/18/2025	3653	60	64.29	2
CMP020	5/3/2025	24465	764	1394.62	42
CMP019	2/5/2025	7214	236	135.93	21
CMP046	6/4/2025	10631	201	298.82	18
CMP044	3/26/2025	31942	964	744.4	37
CMP041	2/22/2025	37210	984	1716.18	32
CMP036	1/4/2025	7112	265	227.31	22
CMP043	6/10/2025	1074	34	56.89	1
CMP047	3/25/2025	47113	2081	963.56	127
CMP016	2/6/2025	36585	920	232.62	35
CMP033	5/6/2025	8147	151	98.61	3
CMP022	5/19/2025	14317	692	862.27	43

tôi muốn viết một chường trình spring boot với các yêu cầu sau:
Chương trình boot up sẽ hiện thị UI đơn giản để user input file cần phân tích
input: là file báo cáo này với các dữ liệu ở trên
điều kiện input: file phải có đầy đủ 6 column headers như data mẫu ở trên, thứ tự cột có thể bị đảo lộn, phải map theo cột
file bị coi là invalid nếu thiếu header, hoặc header không đủ 6 col hoặc 6 col trùng tên nhau
output: 2 file top10_ctr.csv 10 campaign có ctr cao nhất và file top10_cpa.csv 10 campaign có CPA thấp nhất
điều kiện dữ liệu campaign có thể bị invalid ví dụ như ko có campaign_id hay bị lặp lại, một campaign có nhiều dòng (nhiều ngày), ta cộng dồn các cột số liệu theo từng campaign)
Chưa cần code vội, hãy lên logic giải quyết thuật toán sao cho tối ưu nhất và pass được cả những edge cases
Chương trình phải đảm bảo các điều kiện sau:
1. Khi starup có giao diện đơn giản để người dùng import file cần phân tích
2. Cần validate file (đặc biệt là header trước khi xử lí, nếu header không hợp lệ -> báo lỗi)
3. File input có thể rất lớn, cần xử lí để tránh mem leak
4. Cần lên thuật toán xử lí dữ liệu như sau: dọc từng dòng dữ liệu, nếu phát hiện campaign_id mới thì lưu vào hashmap, nếu là campaign_id cũ thì lấy các giá trị đọc được cộng dồn vào các giá trị cũ, nếu row đó bị thiếu dữ liệu cột nào đó -> invalid -> bỏ qua. Khi kết thúc file thì ta có được một Hashmap lưu thông tin unique của từng campaign, từ trong hash map đó lấy ra được top10 ctr cao nhất 
và top10 cpa thấp nhất (nếu bạn có thuật toán xử lí nào tối ưu hơn thì có thể gợi ý)

# 2. Claude confirm the requirements and start coding
    Chốt, bắt đầu code

# 3. After test and realize there some bottleneck point in app, I suggest claude to optimize algorithm
hiện tại file xử lí: 26,843,544 rows trong 7.17 s, flow hiện tại của ứng dụng đang process single thread read row by row và parseRow và validate từng row đang mất nhiều thời gian. Tôi muốn nâng cao hiệu suất bằng cách có thể phân nhỏ file thành từng offset, app chạy theo hướng multi thread, mỗi thread xử lí từng phần offset của file.
Ví dụ file được chia làm 3 offset thì thread pool mở 3 thread để xử lí từng phần tách biệt, mỗi thread đọc file seek từ start -> end, parseRow -> ghi vào hashMap. Kết quả cuối cùng merge N hashmap lại, với cùng campaign thì cộng dồn lại vào map tổng. Không vội sửa vào codebase hãy vẽ lưu đồ thuật toán để tôi review trước và đề xuất nếu có giải pháp tối ưu hơn

# 4. Claude give me the algorithm and I review how it works first

# 5. Claude suggest me that if I keep the file upload flow from the UI, it can only run single-threaded, multi-threading can only be applied when running via CLI.
vậy hãy bỏ web, chỉ chạy cli theo hướng multithread

# 6. Claude start coding and ask me to let claude commit code or I review first
Tôi review trước

# 7. After review and run test I command claude to rewrite file README
hãy viết lại cho tôi file README (có hình ảnh mô tả giải thuật) và độ tối ưu từ single thread sang multi thread và hướng dẫn chạy chương trình

# 8. Require Claude to package app into executable file to easy to run
hãy đóng gói thành file .exe để người chạy không cần build mà có thể trực tiếp chạy và chỉ cần truyền vào input file và output dir, viết lại hướng dẫn README để hướng dẫn chạy