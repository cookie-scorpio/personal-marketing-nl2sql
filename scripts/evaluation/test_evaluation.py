import unittest
from evaluate import compare


class ComparatorTest(unittest.TestCase):
    def setUp(self):
        self.case = {'comparator':'deposit','step':1000000,'upper':10000000}
        self.expected = {i: i+1 for i in range(10)}
        self.result = {'columns':[{'key':'band','role':'DIMENSION'},{'key':'customer_count','role':'MEASURE','unit':'人'}],
                       'rows':[{'band':'%d-%d万' % (i*100,(i+1)*100),'customer_count':i+1} for i in range(10)]}

    def test_correct_dynamic_labels(self):
        self.assertTrue(compare(self.case,self.result,self.expected)[0])

    def test_wrong_scope_or_count(self):
        self.result['rows'][1]['customer_count']+=1
        self.assertFalse(compare(self.case,self.result,self.expected)[0])

    def test_out_of_range_bucket_is_rejected(self):
        self.result['rows'].append({'band':'1000万及以上','customer_count':2})
        self.assertFalse(compare(self.case,self.result,self.expected)[0])

    def test_incorrect_percentage_is_rejected(self):
        self.result['columns'].append({'key':'ratio','role':'MEASURE','unit':'%'})
        for r in self.result['rows']:r['ratio']=50
        self.assertFalse(compare(self.case,self.result,self.expected)[0])

    def test_fractional_count_is_rejected(self):
        self.result['rows'][0]['customer_count']=1.2
        self.assertFalse(compare(self.case,self.result,self.expected)[0])


if __name__ == '__main__':
    unittest.main()
